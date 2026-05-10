"""Kafka sink with Avro encoding using the Confluent wire format
([0x00][4-byte schema id][avro binary]) and Confluent Schema Registry.

The wire bytes are the Avro-encoded ENVELOPE (matching the JSON envelope
shape: _log_type, _log_class, _version, data). The data block uses a
record-specific schema registered per topic.
"""
from __future__ import annotations

import io
import json
import logging
import os
import struct
from typing import Any, Dict, Optional

import fastavro
import requests
from kafka import KafkaProducer

from ._avro_schema import derive_avro_schema  # re-export for backwards compat
from .base import LogEnvelope, LogSink, SinkCallback

__all__ = ["KafkaAvroSink", "derive_avro_schema"]


class KafkaAvroSink(LogSink):
    def __init__(
        self,
        topic: str,
        schema: Dict[str, Any],
        *,
        schema_registry_url: str,
        bootstrap_servers: Optional[str] = None,
        compression: str = "snappy",
        producer_overrides: Optional[dict] = None,
        subject: Optional[str] = None,
    ) -> None:
        self.topic = topic
        self.schema = schema
        self.parsed = fastavro.parse_schema(schema)
        self.sr_url = schema_registry_url.rstrip("/")
        self.subject = subject or f"{topic}-value"
        self._log = logging.getLogger("structured_logging.sink.kafka_avro")
        self.schema_id = self._register_schema()

        bootstrap = bootstrap_servers or os.getenv(
            "KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"
        )
        cfg = dict(
            bootstrap_servers=bootstrap,
            compression_type=None if compression == "none" else compression,
            acks=1,
            retries=3,
            linger_ms=10,
            batch_size=32768,
            api_version=(2, 5, 0),  # see KafkaJsonSink for rationale
        )
        if producer_overrides:
            cfg.update(producer_overrides)
        self.producer = KafkaProducer(**cfg)

    def _register_schema(self) -> int:
        body = json.dumps({"schemaType": "AVRO", "schema": json.dumps(self.schema)})
        r = requests.post(
            f"{self.sr_url}/subjects/{self.subject}/versions",
            headers={"Content-Type": "application/vnd.schemaregistry.v1+json"},
            data=body,
            timeout=10,
        )
        r.raise_for_status()
        sid = int(r.json()["id"])
        self._log.info("registered avro schema id=%d for subject=%s", sid, self.subject)
        return sid

    def name(self) -> str:
        return f"kafka_avro[{self.topic} sr_id={self.schema_id}]"

    def _encode(self, env: LogEnvelope) -> bytes:
        buf = io.BytesIO()
        buf.write(b"\x00")
        buf.write(self.schema_id.to_bytes(4, "big"))
        fastavro.schemaless_writer(buf, self.parsed, env.to_dict())
        return buf.getvalue()

    def publish(self, env: LogEnvelope, callback: SinkCallback = None) -> None:
        try:
            payload = self._encode(env)
        except Exception as e:  # noqa: BLE001
            self._log.error("avro encode threw: %s", e)
            if callback:
                callback(False, e)
            return
        try:
            future = self.producer.send(
                self.topic,
                key=env.key.encode("utf-8") if env.key else None,
                value=payload,
            )
        except Exception as e:  # noqa: BLE001
            self._log.error("kafka send threw: %s", e)
            if callback:
                callback(False, e)
            return
        if callback:
            future.add_callback(lambda _md: callback(True, None))
            future.add_errback(lambda exc: callback(False, exc))

    def flush(self, timeout_s: float = 30.0) -> None:
        self.producer.flush(timeout=timeout_s)

    def close(self) -> None:
        try:
            self.producer.flush(timeout=5)
        finally:
            self.producer.close(timeout=5)
