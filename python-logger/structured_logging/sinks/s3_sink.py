"""S3BatchSink — batches envelopes in memory and flushes to object storage.

Matches the bench/driver/sidecar.py architecture. Two encoders supported:
  * encoding="avro"     -> Avro object container files (Snappy codec)
  * encoding="parquet"  -> Parquet (Zstd level 3)

Bounded by max_records (memory safety), rotate_bytes (raw input bytes), and
rotate_seconds (wall clock). First condition triggers the flush.

NOTE: this sink can run inside the application process if you want a
"single-process sidecar" pattern, OR you can use FileSink in the app and
run a separate sidecar process that reads JSONL and writes to S3 — pick
whichever matches your deployment constraints.
"""
from __future__ import annotations

import io
import json
import logging
import os
import threading
import time
import uuid
from typing import Any, Dict, List, Optional

import boto3

from .base import LogEnvelope, LogSink, SinkCallback


_ENCODER_REGISTRY: Dict[str, Any] = {}


def _register(name: str, fn: Any) -> Any:
    _ENCODER_REGISTRY[name] = fn
    return fn


def _avro_encode(schema: Dict[str, Any], records: List[Dict[str, Any]]) -> bytes:
    import fastavro
    buf = io.BytesIO()
    fastavro.writer(buf, fastavro.parse_schema(schema), records, codec="snappy")
    return buf.getvalue()


def _parquet_encode(arrow_schema, records: List[Dict[str, Any]]) -> bytes:
    import pyarrow as pa
    import pyarrow.parquet as pq
    cols: Dict[str, list] = {f.name: [] for f in arrow_schema}
    for r in records:
        for f in arrow_schema:
            cols[f.name].append(r.get(f.name))
    table = pa.table(cols, schema=arrow_schema)
    buf = io.BytesIO()
    pq.write_table(table, buf, compression="zstd", compression_level=3)
    return buf.getvalue()


class S3BatchSink(LogSink):
    def __init__(
        self,
        *,
        bucket: str,
        encoding: str = "parquet",
        endpoint: Optional[str] = None,
        region: str = "us-east-1",
        path_style: bool = False,
        access_key: Optional[str] = None,
        secret_key: Optional[str] = None,
        rotate_seconds: int = 60,
        rotate_bytes: int = 64 * 1024 * 1024,
        max_records: int = 50_000,
        key_prefix: str = "",
        avro_schema: Optional[Dict[str, Any]] = None,
        parquet_arrow_schema: Optional[Any] = None,
    ) -> None:
        if encoding not in ("avro", "parquet"):
            raise ValueError(f"S3BatchSink: unknown encoding {encoding!r}")
        if encoding == "avro" and avro_schema is None:
            raise ValueError("S3BatchSink: avro encoding requires avro_schema")
        if encoding == "parquet" and parquet_arrow_schema is None:
            # Lazy default: build from avro_schema if both provided; otherwise
            # use a generic envelope-as-JSON-string schema.
            import pyarrow as pa
            parquet_arrow_schema = pa.schema([
                pa.field("_log_type", pa.string()),
                pa.field("_log_class", pa.string()),
                pa.field("_version", pa.string()),
                pa.field("data_json", pa.string()),
                pa.field("key", pa.string()),
            ])
        self.bucket = bucket
        self.encoding = encoding
        self.rotate_seconds = rotate_seconds
        self.rotate_bytes = rotate_bytes
        self.max_records = max_records
        self.key_prefix = key_prefix.rstrip("/")
        self.avro_schema = avro_schema
        self.parquet_arrow_schema = parquet_arrow_schema
        self._log = logging.getLogger("structured_logging.sink.s3")
        self._lock = threading.Lock()
        self._batch: List[Dict[str, Any]] = []
        self._raw_bytes = 0
        self._last_rotate = time.time()
        kw = dict(region_name=region)
        if endpoint:
            kw["endpoint_url"] = endpoint
        if access_key and secret_key:
            kw["aws_access_key_id"] = access_key
            kw["aws_secret_access_key"] = secret_key
        if path_style:
            from botocore.config import Config
            kw["config"] = Config(s3={"addressing_style": "path"})
        self._s3 = boto3.client("s3", **kw)
        # Background flush thread for time-based rotation
        self._stop = threading.Event()
        self._t = threading.Thread(target=self._timer_loop, daemon=True)
        self._t.start()

    def name(self) -> str:
        return f"s3[{self.bucket} enc={self.encoding}]"

    def _envelope_record(self, env: LogEnvelope) -> Dict[str, Any]:
        if self.encoding == "avro":
            return env.to_dict()
        # parquet path: serialize data as JSON string for stability
        return {
            "_log_type": env.log_type,
            "_log_class": env.log_class,
            "_version": env.version,
            "data_json": json.dumps(env.data, default=str, separators=(",", ":")),
            "key": env.key or "",
        }

    def publish(self, env: LogEnvelope, callback: SinkCallback = None) -> None:
        try:
            rec = self._envelope_record(env)
            raw_size = len(json.dumps(env.to_dict(), default=str, separators=(",", ":")))
            with self._lock:
                self._batch.append(rec)
                self._raw_bytes += raw_size
                full = (len(self._batch) >= self.max_records
                        or self._raw_bytes >= self.rotate_bytes)
            if full:
                self._flush()
        except Exception as e:  # noqa: BLE001
            self._log.error("s3 sink publish threw: %s", e)
            if callback:
                callback(False, e)
            return
        if callback:
            callback(True, None)

    def _timer_loop(self) -> None:
        while not self._stop.wait(timeout=1.0):
            now = time.time()
            with self._lock:
                stale = bool(self._batch) and (now - self._last_rotate) >= self.rotate_seconds
            if stale:
                try:
                    self._flush()
                except Exception:
                    self._log.exception("timer flush failed")

    def _flush(self) -> None:
        with self._lock:
            if not self._batch:
                return
            batch = self._batch
            raw = self._raw_bytes
            self._batch = []
            self._raw_bytes = 0
            self._last_rotate = time.time()

        t0 = time.time()
        if self.encoding == "avro":
            body = _avro_encode(self.avro_schema, batch)
            ext = "avro"
        else:
            body = _parquet_encode(self.parquet_arrow_schema, batch)
            ext = "parquet"
        encode_t = time.time() - t0
        ts = time.gmtime()
        key = (
            (self.key_prefix + "/" if self.key_prefix else "")
            + f"y={ts.tm_year}/m={ts.tm_mon:02d}/d={ts.tm_mday:02d}/h={ts.tm_hour:02d}/"
            + f"{int(time.time()*1000)}-{uuid.uuid4().hex[:8]}.{ext}"
        )
        t1 = time.time()
        self._s3.put_object(Bucket=self.bucket, Key=key, Body=body)
        put_t = time.time() - t1
        self._log.info(
            "PUT %s records=%d raw_bytes=%d object_bytes=%d ratio=%.3f encode_s=%.3f put_s=%.3f",
            key, len(batch), raw, len(body), len(body) / max(raw, 1), encode_t, put_t,
        )

    def flush(self, timeout_s: float = 30.0) -> None:
        self._flush()

    def close(self) -> None:
        self._stop.set()
        try:
            self._t.join(timeout=5)
        except Exception:
            pass
        self._flush()
