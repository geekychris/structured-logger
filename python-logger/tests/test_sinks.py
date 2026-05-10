"""Unit tests for the Python sink layer."""
from __future__ import annotations

import json
import os
import tempfile
from pathlib import Path

import pytest

from structured_logging.base_logger import BaseStructuredLogger
from structured_logging.sinks import CompositeSink, FileSink, LogEnvelope, NullSink
from structured_logging.sinks.factory import build_sink


def make_envelope(data=None, key="k1"):
    return LogEnvelope(
        log_type="user_events",
        log_class="UserEvents",
        version="1.0.0",
        data=data or {"user_id": "u1", "n": 1},
        key=key,
    )


# --- envelope shape ---


def test_envelope_to_dict_has_expected_keys():
    env = make_envelope()
    d = env.to_dict()
    assert d.keys() == {"_log_type", "_log_class", "_version", "data"}
    assert d["_log_type"] == "user_events"
    assert d["data"] == {"user_id": "u1", "n": 1}


# --- NullSink ---


def test_nullsink_calls_back_success():
    s = NullSink()
    captured = []
    s.publish(make_envelope(), lambda ok, err: captured.append((ok, err)))
    assert captured == [(True, None)]


# --- FileSink ---


def test_filesink_writes_one_line_per_envelope(tmp_path):
    fs = FileSink(tmp_path / "evt.ndjson")
    fs.publish(make_envelope({"x": 1}))
    fs.publish(make_envelope({"x": 2}))
    fs.close()
    lines = (tmp_path / "evt.ndjson").read_text().splitlines()
    assert len(lines) == 2
    assert json.loads(lines[0])["data"] == {"x": 1}
    assert json.loads(lines[1])["data"] == {"x": 2}


def test_filesink_rotates_at_byte_threshold(tmp_path):
    # One envelope is ~120 bytes. rotate_bytes=200 → second write triggers rotation.
    fs = FileSink(tmp_path / "evt.ndjson", rotate_bytes=200)
    fs.publish(make_envelope({"x": "a" * 50}))
    fs.publish(make_envelope({"x": "a" * 50}))  # crosses 200 → rotate AFTER this
    fs.publish(make_envelope({"x": "a" * 50}))  # lands in fresh file
    fs.close()
    files = sorted(tmp_path.iterdir())
    # Expect: evt.ndjson + evt.ndjson.1
    assert any(f.name == "evt.ndjson" for f in files)
    assert any(f.name == "evt.ndjson.1" for f in files), f"got: {[f.name for f in files]}"


# --- CompositeSink ---


class _Counter:
    def __init__(self, fail=False):
        self.n = 0
        self.fail = fail

    def name(self):
        return "counter(fail=%s)" % self.fail

    def publish(self, env, cb=None):
        self.n += 1
        if cb:
            cb(not self.fail, None if not self.fail else RuntimeError("nope"))

    def flush(self, *_a, **_k):
        pass

    def close(self):
        pass


def test_composite_fans_out_to_all_children():
    c1, c2 = _Counter(), _Counter()
    cs = CompositeSink([c1, c2])
    out = []
    cs.publish(make_envelope(), lambda ok, err: out.append((ok, err)))
    assert c1.n == 1 and c2.n == 1
    assert out == [(True, None)]


def test_composite_reports_failure_when_any_child_fails():
    c1, c2 = _Counter(fail=True), _Counter()
    cs = CompositeSink([c1, c2])
    out = []
    cs.publish(make_envelope(), lambda ok, err: out.append((ok, err)))
    assert out[0][0] is False
    assert isinstance(out[0][1], RuntimeError)


def test_composite_with_empty_children_calls_back_success():
    cs = CompositeSink([])
    out = []
    cs.publish(make_envelope(), lambda ok, err: out.append((ok, err)))
    assert out == [(True, None)]


# --- factory ---


def test_factory_builds_null_when_requested():
    s = build_sink(
        log_type="t", log_class="T", version="1.0.0", topic="t", fields=[],
        transport={"sinks": ["null"]},
    )
    assert isinstance(s, NullSink)


def test_factory_builds_composite_when_multiple(tmp_path):
    s = build_sink(
        log_type="t", log_class="T", version="1.0.0", topic="t", fields=[],
        transport={"sinks": ["file", "null"], "file": {"dir": str(tmp_path)}},
    )
    assert isinstance(s, CompositeSink)
    assert len(s.children) == 2


def test_factory_env_overrides_config(monkeypatch, tmp_path):
    # Even though config says kafka, env says null only
    monkeypatch.setenv("STRUCTURED_LOG_SINKS", "null")
    s = build_sink(
        log_type="t", log_class="T", version="1.0.0", topic="t", fields=[],
        transport={"sinks": ["kafka"]},
    )
    assert isinstance(s, NullSink)


_HAS_FASTAVRO = False
try:
    import fastavro  # noqa: F401
    _HAS_FASTAVRO = True
except ImportError:
    pass

_HAS_BOTO3 = False
try:
    import boto3  # noqa: F401
    _HAS_BOTO3 = True
except ImportError:
    pass


@pytest.mark.skipif(not _HAS_FASTAVRO, reason="fastavro not installed")
def test_factory_avro_requires_schema_registry_url():
    with pytest.raises(ValueError, match="schema_registry_url"):
        build_sink(
            log_type="t", log_class="T", version="1.0.0", topic="t",
            fields=[{"name": "x", "type": "string"}],
            transport={"sinks": ["kafka"], "encoding": "avro"},
        )


@pytest.mark.skipif(not _HAS_BOTO3, reason="boto3 not installed")
def test_factory_s3_requires_bucket():
    with pytest.raises(ValueError, match="bucket"):
        build_sink(
            log_type="t", log_class="T", version="1.0.0", topic="t",
            fields=[{"name": "x", "type": "string"}],
            transport={"sinks": ["s3"]},
        )


# --- BaseStructuredLogger ---


def test_logger_with_injected_sink_publishes_through_it():
    captured = []

    class CapturingSink:
        def name(self):
            return "capturing"

        def publish(self, env, cb=None):
            captured.append(env.to_dict())
            if cb:
                cb(True, None)

        def flush(self, *_a, **_k):
            pass

        def close(self):
            pass

    logger = BaseStructuredLogger(
        topic_name="user-events",
        logger_name="UserEvents",
        log_type="user_events",
        log_class="UserEvents",
        version="1.0.0",
        sink=CapturingSink(),
    )
    logger.publish("user_42", {"event_type": "click", "n": 7})
    assert len(captured) == 1
    assert captured[0]["_log_type"] == "user_events"
    assert captured[0]["data"] == {"event_type": "click", "n": 7}


# --- Avro schema derivation ---


@pytest.mark.skipif(not _HAS_FASTAVRO, reason="fastavro not installed")
def test_avro_schema_derivation_handles_required_and_optional():
    # Don't import KafkaAvroSink (requires fastavro/requests). Just the helper.
    from structured_logging.sinks.kafka_avro import derive_avro_schema
    fields = [
        {"name": "user_id", "type": "string", "required": True},
        {"name": "session_id", "type": "string", "required": False},
        {"name": "duration_ms", "type": "long", "required": False},
        {"name": "props", "type": "map<string,string>", "required": True},
    ]
    schema = derive_avro_schema("user_events", "UserEvents", "1.0.0", fields)
    assert schema["type"] == "record"
    assert schema["name"] == "UserEventsEnvelope"
    data_field = next(f for f in schema["fields"] if f["name"] == "data")
    data_record = data_field["type"]
    fields_by_name = {f["name"]: f for f in data_record["fields"]}
    # required field stays simple type
    assert fields_by_name["user_id"]["type"] == "string"
    # optional field becomes nullable union
    assert isinstance(fields_by_name["session_id"]["type"], list)
    assert "null" in fields_by_name["session_id"]["type"]
    # map type
    assert fields_by_name["props"]["type"]["type"] == "map"
