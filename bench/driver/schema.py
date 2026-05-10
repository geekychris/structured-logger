"""Shared record shape, Avro schema, and deterministic generator."""
import datetime as dt
import os
import random
import string
import time

AVRO_SCHEMA = {
    "type": "record",
    "name": "UserEvent",
    "namespace": "bench",
    "fields": [
        {"name": "produced_at_ns", "type": "long"},
        {"name": "timestamp", "type": "string"},
        {"name": "event_date", "type": "string"},
        {"name": "user_id", "type": "string"},
        {"name": "session_id", "type": "string"},
        {"name": "event_type", "type": "string"},
        {"name": "page_url", "type": ["null", "string"], "default": None},
        {"name": "device_type", "type": ["null", "string"], "default": None},
        {"name": "duration_ms", "type": ["null", "long"], "default": None},
        {"name": "properties", "type": {"type": "map", "values": "string"}, "default": {}},
    ],
}

EVENT_TYPES = ["click", "view", "scroll", "purchase", "signup", "logout", "search"]
DEVICES = ["desktop", "mobile", "tablet"]

_rng = random.Random(int(os.getenv("SEED", "42")))
_session_pool = [f"sess_{i:08x}" for i in range(_rng.randint(2000, 4000))]
_user_pool = [f"user_{i:08d}" for i in range(_rng.randint(5000, 10000))]


def _rand_props(rng):
    n = rng.randint(2, 5)
    return {
        "".join(rng.choices(string.ascii_lowercase, k=8)):
            "".join(rng.choices(string.ascii_letters + string.digits, k=rng.randint(8, 24)))
        for _ in range(n)
    }


def make_record(rng=None):
    """Build one record with produced_at_ns set to NOW (caller may overwrite)."""
    rng = rng or _rng
    now_ns = time.time_ns()
    now = dt.datetime.utcfromtimestamp(now_ns / 1e9)
    return {
        "produced_at_ns": now_ns,
        "timestamp": now.isoformat(timespec="microseconds") + "Z",
        "event_date": now.date().isoformat(),
        "user_id": rng.choice(_user_pool),
        "session_id": rng.choice(_session_pool),
        "event_type": rng.choice(EVENT_TYPES),
        "page_url": "/" + "/".join(
            "".join(rng.choices(string.ascii_lowercase, k=rng.randint(4, 10)))
            for _ in range(rng.randint(1, 3))
        ),
        "device_type": rng.choice(DEVICES),
        "duration_ms": rng.randint(50, 5000),
        "properties": _rand_props(rng),
    }
