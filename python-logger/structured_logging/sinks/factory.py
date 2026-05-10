"""Build a sink (or composite of sinks) from a transport config dict.

Mirror of Java's SinkFactory — the same JSON `transport` block is consumed
on both sides. Env vars override the config so operators can swap transports
at deploy time without rebuilding.

Env var contract:
    STRUCTURED_LOG_SINKS=KAFKA[,FILE,S3,SLF4J,NULL]   # overrides config.transport.sinks
    KAFKA_BOOTSTRAP_SERVERS=...                       # used by kafka sinks
    SCHEMA_REGISTRY_URL=...                           # used by kafka avro sinks
    STRUCTURED_LOG_FILE_DIR=...                       # used by file sink
    STRUCTURED_LOG_S3_BUCKET=...                      # used by s3 sink
    STRUCTURED_LOG_S3_ENDPOINT=...
"""
from __future__ import annotations

import os
from pathlib import Path
from typing import Any, Dict, List, Optional

from .base import CompositeSink, LogSink, NullSink
from .kafka_json import KafkaJsonSink


def _split_csv(value: Optional[str]) -> List[str]:
    if not value:
        return []
    return [p.strip().lower() for p in value.split(",") if p.strip()]


def build_sink(
    *,
    log_type: str,
    log_class: str,
    version: str,
    topic: str,
    fields: List[Dict[str, Any]],
    transport: Optional[Dict[str, Any]] = None,
) -> LogSink:
    """Build the sink chain for a generated logger.

    `transport` comes from the log config's optional `transport` block. Env
    vars override `transport.sinks` and per-sink endpoints.
    """
    transport = dict(transport or {})

    sinks_cfg = _split_csv(os.getenv("STRUCTURED_LOG_SINKS")) or [
        s.lower() for s in transport.get("sinks", ["kafka"])
    ]
    encoding = transport.get("encoding", "json").lower()
    sr_url = os.getenv("SCHEMA_REGISTRY_URL") or transport.get("schema_registry_url")
    kafka_cfg = transport.get("kafka") or {}
    file_cfg = transport.get("file") or {}
    s3_cfg = transport.get("s3") or {}

    built: List[LogSink] = []
    for sink_type in sinks_cfg:
        if sink_type == "null":
            built.append(NullSink())
        elif sink_type == "kafka":
            if encoding == "avro":
                from ._avro_schema import derive_avro_schema
                from .kafka_avro import KafkaAvroSink
                if not sr_url:
                    raise ValueError(
                        f"transport.encoding=avro on kafka sink requires "
                        f"schema_registry_url (or SCHEMA_REGISTRY_URL env)"
                    )
                schema = derive_avro_schema(log_type, log_class, version, fields)
                built.append(KafkaAvroSink(
                    topic=topic,
                    schema=schema,
                    schema_registry_url=sr_url,
                    compression=kafka_cfg.get("compression", "snappy"),
                ))
            else:
                built.append(KafkaJsonSink(
                    topic=topic,
                    compression=kafka_cfg.get("compression", "snappy"),
                ))
        elif sink_type == "file":
            from .file_sink import FileSink
            file_dir = os.getenv("STRUCTURED_LOG_FILE_DIR") or file_cfg.get("dir")
            if not file_dir:
                raise ValueError(
                    "transport.file.dir or STRUCTURED_LOG_FILE_DIR is required for file sink"
                )
            path = Path(file_dir) / f"{log_type}.ndjson"
            built.append(FileSink(
                path,
                rotate_bytes=int(file_cfg.get("rotate_bytes", 64 * 1024 * 1024)),
            ))
        elif sink_type == "s3":
            from .s3_sink import S3BatchSink
            from ._avro_schema import derive_avro_schema
            bucket = os.getenv("STRUCTURED_LOG_S3_BUCKET") or s3_cfg.get("bucket")
            if not bucket:
                raise ValueError(
                    "transport.s3.bucket or STRUCTURED_LOG_S3_BUCKET is required for s3 sink"
                )
            s3_encoding = s3_cfg.get("encoding", "parquet")
            avro_schema = None
            arrow_schema = None
            if s3_encoding == "avro":
                avro_schema = derive_avro_schema(log_type, log_class, version, fields)
            built.append(S3BatchSink(
                bucket=bucket,
                encoding=s3_encoding,
                endpoint=os.getenv("STRUCTURED_LOG_S3_ENDPOINT") or s3_cfg.get("endpoint"),
                region=s3_cfg.get("region", "us-east-1"),
                path_style=bool(s3_cfg.get("path_style", False)),
                access_key=os.getenv("AWS_ACCESS_KEY_ID"),
                secret_key=os.getenv("AWS_SECRET_ACCESS_KEY"),
                rotate_seconds=int(s3_cfg.get("rotate_seconds", 60)),
                rotate_bytes=int(s3_cfg.get("rotate_bytes", 64 * 1024 * 1024)),
                max_records=int(s3_cfg.get("max_records", 50_000)),
                key_prefix=s3_cfg.get("key_prefix", log_type),
                avro_schema=avro_schema,
                parquet_arrow_schema=arrow_schema,
            ))
        elif sink_type == "slf4j":
            # No SLF4J equivalent in Python; route through the stdlib logger
            from .base import LogEnvelope, LogSink, SinkCallback
            import json as _json
            import logging as _logging

            class StdlibLoggerSink(LogSink):
                def __init__(self) -> None:
                    self._log = _logging.getLogger("structured_logging.sink.python_logging")

                def name(self) -> str:
                    return "python_logging"

                def publish(self, env: LogEnvelope, callback: SinkCallback = None) -> None:
                    self._log.info(_json.dumps(env.to_dict(), default=str))
                    if callback:
                        callback(True, None)

            built.append(StdlibLoggerSink())
        else:
            raise ValueError(f"unknown sink type {sink_type!r}")

    if not built:
        return NullSink()
    if len(built) == 1:
        return built[0]
    return CompositeSink(built)
