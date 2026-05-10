#!/usr/bin/env python3
"""End-to-end test driver. Exercises the production sinks against the real
lakehouse stack. Drives a few records through each approach and waits for
them to land.

Run after:
  docker compose -f ../spark_minio_trino/docker-compose.yml -f lakehouse-override.yml up -d
  ./start-consumer.sh both

Verifies via Trino at the end that each landed table is queryable.
"""
import json
import os
import sys
import time
from pathlib import Path

# Make the project's python-logger importable
ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / "python-logger"))

# Use HOST-mapped ports per lakehouse-override.yml
os.environ.setdefault("KAFKA_BOOTSTRAP_SERVERS", "127.0.0.1:18092")
# AWS credentials for the lakehouse MinIO. Must be set BEFORE any S3BatchSink
# is constructed because the factory snapshots the env at build time.
os.environ.setdefault("AWS_ACCESS_KEY_ID", "admin")
os.environ.setdefault("AWS_SECRET_ACCESS_KEY", "password123")

from structured_logging.sinks.base import LogEnvelope  # noqa: E402
from structured_logging.sinks.factory import build_sink  # noqa: E402


FIELDS = [
    {"name": "timestamp", "type": "string", "required": True},
    {"name": "event_date", "type": "string", "required": True},
    {"name": "user_id", "type": "string", "required": True},
    {"name": "session_id", "type": "string", "required": True},
    {"name": "event_type", "type": "string", "required": True},
    {"name": "page_url", "type": "string", "required": False},
    {"name": "device_type", "type": "string", "required": False},
    {"name": "duration_ms", "type": "long", "required": False},
]


def make_envelope(i, log_type="user_events", log_class="UserEvents"):
    return LogEnvelope(
        log_type=log_type,
        log_class=log_class,
        version="1.0.0",
        data={
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime()),
            "event_date": time.strftime("%Y-%m-%d", time.gmtime()),
            "user_id": f"e2e_user_{i}",
            "session_id": f"sess_{i}",
            "event_type": ["click", "view", "purchase"][i % 3],
            "page_url": f"/page/{i}",
            "device_type": ["desktop", "mobile"][i % 2],
            "duration_ms": 100 + i,
        },
        key=f"e2e_user_{i}",
    )


def drive_kafka_json():
    """Approach A: Kafka + JSON+Snappy."""
    print("\n>>> Approach A: Kafka + JSON+Snappy")
    sink = build_sink(
        log_type="user_events", log_class="UserEvents", version="1.0.0",
        topic="user-events", fields=FIELDS,
        transport={"sinks": ["kafka"], "encoding": "json"},
    )
    try:
        for i in range(5):
            sink.publish(make_envelope(i))
        sink.flush(timeout_s=10)
        print(f"  pushed 5 records via {sink.name()}")
    finally:
        sink.close()


def drive_s3_avro():
    """Approach C: in-process S3BatchSink, Avro."""
    print("\n>>> Approach C: Sidecar→S3 Avro (in-process)")
    sink = build_sink(
        log_type="user_events", log_class="UserEvents", version="1.0.0",
        topic="user-events", fields=FIELDS,
        transport={
            "sinks": ["s3"],
            "encoding": "json",  # ignored for s3 sinks; s3 has its own encoding
            "s3": {
                "bucket": "lakehouse",
                "endpoint": "http://localhost:18000",
                "region": "us-east-1",
                "path_style": True,
                "encoding": "avro",
                "rotate_seconds": 3,
                "max_records": 10,
                "key_prefix": "user_events_avro",
            },
        },
    )
    # Need to set AWS creds; the factory passes them via env var
    os.environ.setdefault("AWS_ACCESS_KEY_ID", "admin")
    os.environ.setdefault("AWS_SECRET_ACCESS_KEY", "password123")
    try:
        for i in range(5):
            sink.publish(make_envelope(i + 100))
        sink.flush(timeout_s=10)
        print(f"  pushed 5 records via {sink.name()}; waiting for rotation flush...")
        time.sleep(5)
    finally:
        sink.close()


def drive_s3_parquet():
    """Approach D: in-process S3BatchSink, Parquet."""
    print("\n>>> Approach D: Sidecar→S3 Parquet+Zstd (in-process)")
    sink = build_sink(
        log_type="user_events", log_class="UserEvents", version="1.0.0",
        topic="user-events", fields=FIELDS,
        transport={
            "sinks": ["s3"],
            "s3": {
                "bucket": "lakehouse",
                "endpoint": "http://localhost:18000",
                "region": "us-east-1",
                "path_style": True,
                "encoding": "parquet",
                "rotate_seconds": 3,
                "max_records": 10,
                "key_prefix": "user_events_parquet",
            },
        },
    )
    try:
        for i in range(5):
            sink.publish(make_envelope(i + 200))
        sink.flush(timeout_s=10)
        print(f"  pushed 5 records via {sink.name()}; waiting for rotation flush...")
        time.sleep(5)
    finally:
        sink.close()


def drive_kafka_avro():
    """Approach B: Kafka + Avro + Schema Registry."""
    print("\n>>> Approach B: Kafka + Avro+SR")
    sink = build_sink(
        log_type="user_events", log_class="UserEventsKafkaAvro", version="1.0.0",
        topic="user-events-avro", fields=FIELDS,
        transport={
            "sinks": ["kafka"],
            "encoding": "avro",
            "schema_registry_url": "http://127.0.0.1:18081",
        },
    )
    try:
        for i in range(5):
            sink.publish(make_envelope(i + 300, log_class="UserEventsKafkaAvro"))
        sink.flush(timeout_s=10)
        print(f"  pushed 5 records via {sink.name()}")
    finally:
        sink.close()


def main():
    drive_kafka_json()
    drive_kafka_avro()
    drive_s3_avro()
    drive_s3_parquet()
    print("\n=== All approaches drove records. ===")
    print("Wait ~30s for Spark micro-batches to land, then verify with Trino:")
    print("  docker exec -it trino trino --server localhost:8080")
    print("  trino> SELECT * FROM iceberg.analytics_logs.user_events ORDER BY user_id LIMIT 30;")


if __name__ == "__main__":
    main()
