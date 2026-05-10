"""File-tailing sidecar that batches and uploads to MinIO.

MODE=avro     -> upload Avro+Snappy object container files
MODE=parquet  -> upload Parquet+Zstd files

Bounded batch sizing: flush whenever the batch reaches MAX_RECORDS, ROTATE_S
seconds, or ROTATE_BYTES of raw JSON — whichever first. The HARD cap on records
guards against memory blowup if the source file already has a backlog when we
attach (we tail from offset 0 so we don't miss data the driver wrote first).
"""
import io
import json
import os
import time
import uuid
from pathlib import Path

import boto3
import fastavro
import pyarrow as pa
import pyarrow.parquet as pq

import schema as S

MODE = os.environ["MODE"]
LOG_DIR = Path(os.environ["LOG_DIR"])
BUCKET = os.environ["MINIO_BUCKET"]
ROTATE_S = int(os.getenv("ROTATE_S", "30"))
ROTATE_BYTES = int(os.getenv("ROTATE_BYTES", str(16 * 1024 * 1024)))
MAX_RECORDS = int(os.getenv("MAX_RECORDS", "50000"))


def _s3():
    return boto3.client(
        "s3",
        endpoint_url=os.environ["MINIO_ENDPOINT"],
        aws_access_key_id=os.environ["MINIO_ACCESS_KEY"],
        aws_secret_access_key=os.environ["MINIO_SECRET_KEY"],
        region_name="us-east-1",
    )


def _encode_avro(records):
    buf = io.BytesIO()
    fastavro.writer(buf, fastavro.parse_schema(S.AVRO_SCHEMA), records, codec="snappy")
    return buf.getvalue()


# Build pyarrow schema once. Use struct-of-keyed string columns? No — properties
# are dynamic so we serialize them to a JSON string column. Much faster than
# pa.map_(); cost is querying convenience (downstream needs JSON_EXTRACT) but
# this is a transport benchmark, not a query benchmark.
_PARQUET_SCHEMA = pa.schema([
    pa.field("produced_at_ns", pa.int64()),
    pa.field("timestamp", pa.string()),
    pa.field("event_date", pa.string()),
    pa.field("user_id", pa.string()),
    pa.field("session_id", pa.string()),
    pa.field("event_type", pa.string()),
    pa.field("page_url", pa.string()),
    pa.field("device_type", pa.string()),
    pa.field("duration_ms", pa.int64()),
    pa.field("properties_json", pa.string()),
])


def _encode_parquet(records):
    cols = {f.name: [] for f in _PARQUET_SCHEMA}
    for r in records:
        cols["produced_at_ns"].append(r.get("produced_at_ns"))
        cols["timestamp"].append(r.get("timestamp"))
        cols["event_date"].append(r.get("event_date"))
        cols["user_id"].append(r.get("user_id"))
        cols["session_id"].append(r.get("session_id"))
        cols["event_type"].append(r.get("event_type"))
        cols["page_url"].append(r.get("page_url"))
        cols["device_type"].append(r.get("device_type"))
        cols["duration_ms"].append(r.get("duration_ms"))
        cols["properties_json"].append(json.dumps(r.get("properties") or {}, separators=(",", ":")))
    table = pa.table(cols, schema=_PARQUET_SCHEMA)
    buf = io.BytesIO()
    pq.write_table(table, buf, compression="zstd", compression_level=3)
    return buf.getvalue()


def _flush(s3, batch, raw_bytes):
    if not batch:
        return 0
    t0 = time.time()
    if MODE == "avro":
        body = _encode_avro(batch)
        ext = "avro"
    else:
        body = _encode_parquet(batch)
        ext = "parquet"
    enc_t = time.time() - t0
    t1 = time.time()
    key = f"y={time.strftime('%Y')}/m={time.strftime('%m')}/d={time.strftime('%d')}/h={time.strftime('%H')}/" \
          f"{int(time.time()*1000)}-{uuid.uuid4().hex[:8]}.{ext}"
    s3.put_object(Bucket=BUCKET, Key=key, Body=body)
    put_t = time.time() - t1
    print(f"[sidecar-{MODE}] PUT {key} records={len(batch)} raw_bytes={raw_bytes} "
          f"object_bytes={len(body)} ratio={len(body)/max(raw_bytes,1):.3f} "
          f"encode_s={enc_t:.3f} put_s={put_t:.3f}", flush=True)
    return len(body)


def main():
    s3 = _s3()
    path = LOG_DIR / "events.jsonl"
    for _ in range(60):
        if path.exists():
            break
        time.sleep(1)
    else:
        print(f"[sidecar-{MODE}] log file never appeared at {path}", flush=True)
        return

    print(f"[sidecar-{MODE}] tailing {path} bucket={BUCKET} "
          f"rotate_s={ROTATE_S} rotate_bytes={ROTATE_BYTES} max_records={MAX_RECORDS}", flush=True)
    fh = open(path, "rb")

    batch = []
    raw_bytes = 0
    last_rotate = time.time()
    pending = b""
    idle_grace_at = None
    IDLE_GRACE_S = 30
    READ_CHUNK = 256 * 1024  # smaller chunks => more frequent rotation checks

    while True:
        chunk = fh.read(READ_CHUNK)
        if chunk:
            idle_grace_at = None
            pending += chunk
            while True:
                nl = pending.find(b"\n")
                if nl < 0:
                    break
                line = pending[:nl]
                pending = pending[nl + 1:]
                if not line:
                    continue
                try:
                    rec = json.loads(line)
                except json.JSONDecodeError:
                    continue
                batch.append(rec)
                raw_bytes += len(line) + 1
                # Hard cap to avoid unbounded memory if encoding is slow.
                if len(batch) >= MAX_RECORDS:
                    _flush(s3, batch, raw_bytes)
                    batch = []
                    raw_bytes = 0
                    last_rotate = time.time()
        else:
            time.sleep(0.05)
            if idle_grace_at is None:
                idle_grace_at = time.time()

        now = time.time()
        if batch and (now - last_rotate >= ROTATE_S or raw_bytes >= ROTATE_BYTES):
            _flush(s3, batch, raw_bytes)
            batch = []
            raw_bytes = 0
            last_rotate = now

        if idle_grace_at is not None and (now - idle_grace_at) >= IDLE_GRACE_S:
            if batch:
                _flush(s3, batch, raw_bytes)
            print(f"[sidecar-{MODE}] idle, exiting", flush=True)
            return


if __name__ == "__main__":
    main()
