"""Multi-mode load driver.

MODE=kafka-json   — Confluent producer with JSON value, snappy compression.
MODE=kafka-avro   — Confluent producer with Avro value via Schema Registry.
MODE=file-jsonl   — Write JSON lines to a file in LOG_DIR for a sidecar to ship.

Each record carries produced_at_ns so the consumer can compute end-to-end latency.
RPS is enforced via a token-bucket loop. Records produced after WARMUP_S have a flag
set so the consumer can drop warmup samples from latency stats.
"""
import io
import json
import os
import random
import sys
import time
from pathlib import Path

import fastavro
import requests
from confluent_kafka import Producer

import schema as S

MODE = os.environ["MODE"]
RPS = int(os.getenv("RPS", "2000"))
DURATION_S = int(os.getenv("DURATION_S", "300"))
WARMUP_S = int(os.getenv("WARMUP_S", "30"))


def _kafka_producer(extra=None):
    cfg = {
        "bootstrap.servers": os.environ["KAFKA_BOOTSTRAP"],
        "compression.type": os.getenv("COMPRESSION", "snappy"),
        "linger.ms": 10,
        "batch.size": 65536,
        "acks": "1",
        "queue.buffering.max.messages": 1_000_000,
        "queue.buffering.max.kbytes": 1_048_576,
    }
    if extra:
        cfg.update(extra)
    return Producer(cfg)


def _register_avro_schema(sr_url, subject, schema_str):
    r = requests.post(
        f"{sr_url}/subjects/{subject}/versions",
        headers={"Content-Type": "application/vnd.schemaregistry.v1+json"},
        data=json.dumps({"schemaType": "AVRO", "schema": schema_str}),
        timeout=10,
    )
    r.raise_for_status()
    return r.json()["id"]


def _avro_encode(parsed_schema, sr_id, record):
    """Confluent wire format: magic byte 0x00 + 4-byte schema id (BE) + Avro binary."""
    buf = io.BytesIO()
    buf.write(b"\x00")
    buf.write(sr_id.to_bytes(4, "big"))
    fastavro.schemaless_writer(buf, parsed_schema, record)
    return buf.getvalue()


def run_kafka_json():
    topic = os.environ["TOPIC"]
    p = _kafka_producer()
    rng = random.Random(1234)
    print(f"[driver-kafka-json] starting {RPS} rps -> {topic}", flush=True)
    _drive(rng, lambda rec: p.produce(topic, value=json.dumps(rec).encode("utf-8"),
                                      key=rec["user_id"].encode("utf-8")), poll=p.poll, flush=p.flush)


def run_kafka_avro():
    topic = os.environ["TOPIC"]
    sr_url = os.environ["SCHEMA_REGISTRY_URL"]
    schema_str = json.dumps(S.AVRO_SCHEMA)
    # poll until SR ready
    for _ in range(60):
        try:
            requests.get(sr_url + "/subjects", timeout=2).raise_for_status()
            break
        except Exception:
            time.sleep(1)
    sr_id = _register_avro_schema(sr_url, f"{topic}-value", schema_str)
    parsed = fastavro.parse_schema(S.AVRO_SCHEMA)
    p = _kafka_producer()
    rng = random.Random(1234)
    print(f"[driver-kafka-avro] schema id={sr_id}, {RPS} rps -> {topic}", flush=True)
    _drive(rng,
           lambda rec: p.produce(topic,
                                 value=_avro_encode(parsed, sr_id, rec),
                                 key=rec["user_id"].encode("utf-8")),
           poll=p.poll, flush=p.flush)


def run_file_jsonl():
    log_dir = Path(os.environ["LOG_DIR"])
    log_dir.mkdir(parents=True, exist_ok=True)
    # Single appended log file; sidecar tails it. Using line-buffered mode.
    fh = open(log_dir / "events.jsonl", "ab", buffering=0)
    rng = random.Random(1234)
    print(f"[driver-file-jsonl] {RPS} rps -> {log_dir}/events.jsonl", flush=True)
    buf = bytearray()
    BUF_LIMIT = 64 * 1024  # write in ~64KB chunks to amortise syscalls

    def emit(rec):
        nonlocal buf
        buf += json.dumps(rec, separators=(",", ":")).encode("utf-8") + b"\n"
        if len(buf) >= BUF_LIMIT:
            fh.write(bytes(buf))
            buf.clear()

    def _flush(_=None):
        nonlocal buf
        if buf:
            fh.write(bytes(buf))
            buf.clear()

    try:
        _drive(rng, emit, poll=lambda *_a, **_k: None, flush=_flush)
    finally:
        _flush()
        fh.close()


def _drive(rng, emit_fn, poll, flush):
    """Token-bucket loop: pace at RPS, mark warmup boundary, run for DURATION_S."""
    start = time.monotonic()
    deadline = start + DURATION_S
    warmup_end = start + WARMUP_S
    count = 0
    next_t = start
    interval = 1.0 / RPS
    last_log = start
    while True:
        now = time.monotonic()
        if now >= deadline:
            break
        if now < next_t:
            sleep_t = next_t - now
            # micro-sleep; for very high RPS we just spin
            if sleep_t > 0.0005:
                time.sleep(sleep_t)
            continue
        rec = S.make_record(rng)
        # produced_at_ns is set in make_record; flag warmup with a sentinel field
        if now < warmup_end:
            rec["properties"]["__warmup"] = "1"
        emit_fn(rec)
        count += 1
        next_t += interval
        # Periodic Kafka producer poll to drain delivery callbacks
        if count % 1000 == 0:
            poll(0)
        if now - last_log >= 10.0:
            elapsed = now - start
            print(f"[driver] t={elapsed:6.1f}s sent={count:>10d} eff_rps={count/elapsed:8.1f}", flush=True)
            last_log = now
    flush(30)
    elapsed = time.monotonic() - start
    print(f"[driver] DONE sent={count} duration={elapsed:.2f}s eff_rps={count/elapsed:.1f}", flush=True)


if __name__ == "__main__":
    if MODE == "kafka-json":
        run_kafka_json()
    elif MODE == "kafka-avro":
        run_kafka_avro()
    elif MODE == "file-jsonl":
        run_file_jsonl()
    else:
        print(f"unknown MODE={MODE}", file=sys.stderr)
        sys.exit(2)
