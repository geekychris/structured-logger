"""Multi-mode landing-watcher / latency probe.

MODE=kafka-json     subscribes to TOPIC, decodes JSON, computes latency.
MODE=kafka-avro     subscribes to TOPIC, decodes Confluent-wire-format Avro.
MODE=minio-avro     polls MINIO_BUCKET for new objects, decodes Avro.
MODE=minio-parquet  polls MINIO_BUCKET for new objects, decodes Parquet.

For every record observed (after WARMUP_S), records:
    landed_at_ns - produced_at_ns

Outputs to /results/<RUN_ID>/latency.csv:
    second_since_start, count, p50_ms, p95_ms, p99_ms, max_ms, bytes_in
And /results/<RUN_ID>/summary.json with totals.
"""
import csv
import io
import json
import os
import struct
import time
from pathlib import Path

import boto3
import fastavro
import pyarrow.parquet as pq
import requests

import schema as S

MODE = os.environ["MODE"]
RUN_ID = os.environ.get("RUN_ID", MODE)
DURATION_S = int(os.getenv("DURATION_S", "300"))
WARMUP_S = int(os.getenv("WARMUP_S", "30"))
RESULTS = Path("/results") / RUN_ID
RESULTS.mkdir(parents=True, exist_ok=True)


class Stats:
    """Per-second bucketed latency stats. Memory-cheap: store raw samples per second
    and percentile at write time. With <100k samples/sec that's fine."""

    def __init__(self):
        self.buckets = {}  # second -> list[float ms]
        self.bytes_in = {}  # second -> int
        self.total = 0
        self.total_bytes = 0
        self.start = time.time()

    def record(self, latency_ms, raw_bytes, t=None):
        t = t or time.time()
        sec = int(t - self.start)
        self.buckets.setdefault(sec, []).append(latency_ms)
        self.bytes_in[sec] = self.bytes_in.get(sec, 0) + raw_bytes
        self.total += 1
        self.total_bytes += raw_bytes

    def write(self, path_csv, path_json, *, warmup_s):
        rows = []
        all_lat = []
        all_bytes = 0
        for sec in sorted(self.buckets):
            samples = self.buckets[sec]
            samples_sorted = sorted(samples)
            n = len(samples_sorted)
            def pct(p):
                if n == 0:
                    return 0.0
                k = max(0, min(n - 1, int(round((p / 100.0) * (n - 1)))))
                return samples_sorted[k]
            row = {
                "second": sec,
                "count": n,
                "p50_ms": round(pct(50), 3),
                "p95_ms": round(pct(95), 3),
                "p99_ms": round(pct(99), 3),
                "max_ms": round(samples_sorted[-1], 3) if n else 0.0,
                "bytes_in": self.bytes_in.get(sec, 0),
            }
            rows.append(row)
            if sec >= warmup_s:
                all_lat.extend(samples)
                all_bytes += self.bytes_in.get(sec, 0)
        with open(path_csv, "w") as f:
            w = csv.DictWriter(f, fieldnames=rows[0].keys() if rows else
                               ["second", "count", "p50_ms", "p95_ms", "p99_ms", "max_ms", "bytes_in"])
            w.writeheader()
            for r in rows:
                w.writerow(r)
        all_lat.sort()
        n = len(all_lat)
        def pct(p):
            if n == 0:
                return 0.0
            k = max(0, min(n - 1, int(round((p / 100.0) * (n - 1)))))
            return all_lat[k]
        summary = {
            "run_id": RUN_ID,
            "mode": MODE,
            "warmup_s": warmup_s,
            "duration_s_observed": rows[-1]["second"] if rows else 0,
            "records_total": self.total,
            "records_after_warmup": n,
            "bytes_after_warmup": all_bytes,
            "p50_ms": round(pct(50), 3),
            "p95_ms": round(pct(95), 3),
            "p99_ms": round(pct(99), 3),
            "p999_ms": round(pct(99.9), 3),
            "max_ms": round(all_lat[-1], 3) if n else 0.0,
        }
        with open(path_json, "w") as f:
            json.dump(summary, f, indent=2)
        print(f"[consumer-{MODE}] wrote {path_csv} and {path_json}: {summary}", flush=True)


def _is_warmup(rec):
    return rec.get("properties", {}).get("__warmup") == "1"


def consume_kafka_json():
    from confluent_kafka import Consumer
    c = Consumer({
        "bootstrap.servers": os.environ["KAFKA_BOOTSTRAP"],
        "group.id": f"bench-{RUN_ID}-{int(time.time())}",
        "auto.offset.reset": "earliest",
        "enable.auto.commit": False,
        "fetch.min.bytes": 1,
    })
    c.subscribe([os.environ["TOPIC"]])
    stats = Stats()
    deadline = time.time() + DURATION_S + 60
    print(f"[consumer-kafka-json] subscribed to {os.environ['TOPIC']}", flush=True)
    while time.time() < deadline:
        msg = c.poll(0.5)
        if msg is None:
            continue
        if msg.error():
            continue
        landed_ns = time.time_ns()
        try:
            rec = json.loads(msg.value())
        except Exception:
            continue
        if _is_warmup(rec):
            continue
        latency_ms = (landed_ns - rec["produced_at_ns"]) / 1e6
        stats.record(latency_ms, len(msg.value()))
    c.close()
    stats.write(RESULTS / "latency.csv", RESULTS / "summary.json", warmup_s=0)


def consume_kafka_avro():
    from confluent_kafka import Consumer
    sr_url = os.environ["SCHEMA_REGISTRY_URL"]
    parsed = fastavro.parse_schema(S.AVRO_SCHEMA)
    schema_cache = {}
    def decode(payload):
        if payload[0] != 0:
            raise ValueError("not Confluent wire format")
        sid = struct.unpack(">I", payload[1:5])[0]
        sch = schema_cache.get(sid)
        if sch is None:
            r = requests.get(f"{sr_url}/schemas/ids/{sid}", timeout=5)
            r.raise_for_status()
            sch = fastavro.parse_schema(json.loads(r.json()["schema"]))
            schema_cache[sid] = sch
        return fastavro.schemaless_reader(io.BytesIO(payload[5:]), sch)

    c = Consumer({
        "bootstrap.servers": os.environ["KAFKA_BOOTSTRAP"],
        "group.id": f"bench-{RUN_ID}-{int(time.time())}",
        "auto.offset.reset": "earliest",
        "enable.auto.commit": False,
        "fetch.min.bytes": 1,
    })
    c.subscribe([os.environ["TOPIC"]])
    stats = Stats()
    deadline = time.time() + DURATION_S + 60
    print(f"[consumer-kafka-avro] subscribed to {os.environ['TOPIC']}", flush=True)
    while time.time() < deadline:
        msg = c.poll(0.5)
        if msg is None or msg.error():
            continue
        landed_ns = time.time_ns()
        try:
            rec = decode(msg.value())
        except Exception as e:
            print(f"[consumer-kafka-avro] decode err: {e}", flush=True)
            continue
        if _is_warmup(rec):
            continue
        latency_ms = (landed_ns - rec["produced_at_ns"]) / 1e6
        stats.record(latency_ms, len(msg.value()))
    c.close()
    stats.write(RESULTS / "latency.csv", RESULTS / "summary.json", warmup_s=0)


def _s3():
    return boto3.client(
        "s3",
        endpoint_url=os.environ["MINIO_ENDPOINT"],
        aws_access_key_id=os.environ["MINIO_ACCESS_KEY"],
        aws_secret_access_key=os.environ["MINIO_SECRET_KEY"],
        region_name="us-east-1",
    )


def consume_minio(mode):
    s3 = _s3()
    bucket = os.environ["MINIO_BUCKET"]
    seen = set()
    stats = Stats()
    deadline = time.time() + DURATION_S + 120  # extra grace for last sidecar flush
    print(f"[consumer-{mode}] watching s3://{bucket}/", flush=True)
    parsed_avro = fastavro.parse_schema(S.AVRO_SCHEMA)

    while time.time() < deadline:
        try:
            r = s3.list_objects_v2(Bucket=bucket)
        except Exception as e:
            print(f"[consumer-{mode}] list err: {e}", flush=True)
            time.sleep(2)
            continue
        new_keys = []
        for obj in r.get("Contents", []) or []:
            if obj["Key"] not in seen:
                new_keys.append(obj["Key"])
        for key in new_keys:
            seen.add(key)
            landed_ns = time.time_ns()
            try:
                body = s3.get_object(Bucket=bucket, Key=key)["Body"].read()
            except Exception as e:
                print(f"[consumer-{mode}] get err {key}: {e}", flush=True)
                continue
            obj_size = len(body)
            n_recs = 0
            sum_lat_ms = 0.0
            if mode == "minio-avro":
                for rec in fastavro.reader(io.BytesIO(body), parsed_avro):
                    if _is_warmup(rec):
                        continue
                    latency_ms = (landed_ns - rec["produced_at_ns"]) / 1e6
                    # Per-record bytes attribution: amortise object bytes evenly
                    stats.record(latency_ms, 0)
                    n_recs += 1
                    sum_lat_ms += latency_ms
            else:  # minio-parquet
                table = pq.read_table(io.BytesIO(body))
                produced = table.column("produced_at_ns").to_pylist()
                # Sidecar serializes properties as JSON string (properties_json)
                # for fast pyarrow encoding. Decode here only to filter warmup flag.
                props_json = table.column("properties_json").to_pylist()
                for prod_ns, pj in zip(produced, props_json):
                    if pj and '"__warmup":"1"' in pj:
                        continue
                    latency_ms = (landed_ns - prod_ns) / 1e6
                    stats.record(latency_ms, 0)
                    n_recs += 1
                    sum_lat_ms += latency_ms
            # Attribute the whole object's bytes to the second of landing
            stats.bytes_in[int(time.time() - stats.start)] = \
                stats.bytes_in.get(int(time.time() - stats.start), 0) + obj_size
            stats.total_bytes += obj_size
            print(f"[consumer-{mode}] +{n_recs} recs from {key} obj_bytes={obj_size} "
                  f"avg_lat_ms={sum_lat_ms/max(n_recs,1):.1f}", flush=True)
        time.sleep(2)
    stats.write(RESULTS / "latency.csv", RESULTS / "summary.json", warmup_s=0)


if __name__ == "__main__":
    if MODE == "kafka-json":
        consume_kafka_json()
    elif MODE == "kafka-avro":
        consume_kafka_avro()
    elif MODE in ("minio-avro", "minio-parquet"):
        consume_minio(MODE)
    else:
        raise SystemExit(f"unknown MODE={MODE}")
