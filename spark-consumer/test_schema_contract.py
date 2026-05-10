#!/usr/bin/env python3
"""Schema-contract test between the production S3BatchSink (Python) and
the s3-to-iceberg-consumer.py (Spark).

Runs without Spark. Validates that:

  1. S3BatchSink writes Avro objects whose schema exactly matches what
     s3-to-iceberg-consumer.py expects to find when it does
     readStream.format("avro").schema(envelope_schema).
  2. S3BatchSink writes Parquet objects whose schema matches the flat shape
     the consumer reads (with `data_json` decoded by from_json).
  3. The envelope nesting matches (`_log_type`, `_log_class`, `_version`,
     `data` for avro; same plus `data_json` for parquet).

Requires bench MinIO running (`cd bench && docker compose up -d minio
minio-init`).
"""
import io
import json
import os
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "python-logger"))

from structured_logging.sinks.s3_sink import S3BatchSink  # noqa: E402
from structured_logging.sinks._avro_schema import derive_avro_schema  # noqa: E402
from structured_logging.sinks.base import LogEnvelope  # noqa: E402
import boto3
import fastavro
import pyarrow.parquet as pq


MINIO = dict(
    endpoint="http://localhost:9000",
    access_key="bench",
    secret_key="benchpass",
    region="us-east-1",
)
BUCKET_AVRO = "schema-contract-avro"
BUCKET_PARQUET = "schema-contract-parquet"

FIELDS = [
    {"name": "user_id", "type": "string", "required": True},
    {"name": "event_type", "type": "string", "required": True},
    {"name": "duration_ms", "type": "long", "required": False},
    {"name": "props", "type": "map<string,string>", "required": False},
]


def s3_client():
    return boto3.client(
        "s3",
        endpoint_url=MINIO["endpoint"],
        aws_access_key_id=MINIO["access_key"],
        aws_secret_access_key=MINIO["secret_key"],
        region_name=MINIO["region"],
    )


def ensure_bucket(s3, name):
    try:
        s3.create_bucket(Bucket=name)
    except s3.exceptions.BucketAlreadyOwnedByYou:
        pass
    except Exception:
        pass


def make_records(n=10):
    out = []
    for i in range(n):
        out.append(LogEnvelope(
            log_type="user_events",
            log_class="UserEvents",
            version="1.0.0",
            data={
                "user_id": f"u{i}",
                "event_type": ["click", "view"][i % 2],
                "duration_ms": 100 + i,
                "props": {"k": "v"},
            },
            key=f"u{i}",
        ))
    return out


# ---------- approach C: Avro contract ----------


def test_avro_contract():
    s3 = s3_client()
    ensure_bucket(s3, BUCKET_AVRO)
    sink = S3BatchSink(
        bucket=BUCKET_AVRO,
        encoding="avro",
        endpoint=MINIO["endpoint"],
        path_style=True,
        access_key=MINIO["access_key"],
        secret_key=MINIO["secret_key"],
        rotate_seconds=2,
        max_records=5,
        avro_schema=derive_avro_schema("user_events", "UserEvents", "1.0.0", FIELDS),
        key_prefix="contract",
    )
    for env in make_records(7):
        sink.publish(env)
    sink.close()
    time.sleep(1)

    # List + read back
    objs = s3.list_objects_v2(Bucket=BUCKET_AVRO).get("Contents", [])
    assert objs, "no objects written"
    print(f"[avro] wrote {len(objs)} object(s)")
    body = s3.get_object(Bucket=BUCKET_AVRO, Key=objs[0]["Key"])["Body"].read()
    reader = fastavro.reader(io.BytesIO(body))
    records = list(reader)
    assert records, "object had no records"
    rec = records[0]
    # schema-contract assertions: keys are exactly what s3-to-iceberg-consumer.py
    # expects when it does df.select("data.*")
    assert set(rec.keys()) == {"_log_type", "_log_class", "_version", "data"}, \
        f"avro envelope keys mismatch: {set(rec.keys())}"
    assert rec["_log_type"] == "user_events"
    assert rec["_log_class"] == "UserEvents"
    assert rec["_version"] == "1.0.0"
    data_keys = set(rec["data"].keys())
    expected_data_keys = {f["name"] for f in FIELDS}
    assert data_keys == expected_data_keys, \
        f"avro data keys mismatch: {data_keys} vs {expected_data_keys}"
    # types: optional fields are union[null, T]; on the wire they show up as the value
    assert rec["data"]["user_id"] == "u0"
    assert rec["data"]["duration_ms"] == 100
    assert rec["data"]["props"] == {"k": "v"}
    print("[avro] ✓ envelope shape matches what s3-to-iceberg-consumer expects")
    print(f"[avro] ✓ data fields: {sorted(data_keys)}")


# ---------- approach D: Parquet contract ----------


def test_parquet_contract():
    s3 = s3_client()
    ensure_bucket(s3, BUCKET_PARQUET)
    sink = S3BatchSink(
        bucket=BUCKET_PARQUET,
        encoding="parquet",
        endpoint=MINIO["endpoint"],
        path_style=True,
        access_key=MINIO["access_key"],
        secret_key=MINIO["secret_key"],
        rotate_seconds=2,
        max_records=5,
        # parquet encoding doesn't need an avro_schema; it uses the flat
        # default schema (data serialized to a JSON string column)
        key_prefix="contract",
    )
    for env in make_records(7):
        sink.publish(env)
    sink.close()
    time.sleep(1)

    objs = s3.list_objects_v2(Bucket=BUCKET_PARQUET).get("Contents", [])
    assert objs, "no parquet objects written"
    print(f"[parquet] wrote {len(objs)} object(s)")
    body = s3.get_object(Bucket=BUCKET_PARQUET, Key=objs[0]["Key"])["Body"].read()
    table = pq.read_table(io.BytesIO(body))
    cols = set(table.column_names)
    expected = {"_log_type", "_log_class", "_version", "data_json", "key"}
    assert cols == expected, f"parquet column mismatch: {cols} vs {expected}"
    print(f"[parquet] ✓ flat schema matches consumer expectation: {sorted(cols)}")
    # Decode one row's data_json and check fields
    first = table.to_pylist()[0]
    decoded = json.loads(first["data_json"])
    assert set(decoded.keys()) == {f["name"] for f in FIELDS}
    print(f"[parquet] ✓ data_json decodes to expected fields: {sorted(decoded.keys())}")


def main():
    print("=== schema-contract test (no Spark needed) ===\n")
    test_avro_contract()
    print()
    test_parquet_contract()
    print("\n=== ALL CONTRACTS PASS ===")


if __name__ == "__main__":
    main()
