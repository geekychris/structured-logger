"""Kafka sink with JSON envelope encoding. Snappy compression by default
(matches the Java KafkaSink). Uses kafka-python under the hood; if
confluent-kafka is installed it will be picked up — kafka-python is the
default to keep the dependency footprint small."""
from __future__ import annotations

import logging
import os
from typing import Optional

from kafka import KafkaProducer

from .base import LogEnvelope, LogSink, SinkCallback, envelope_to_json_bytes


class KafkaJsonSink(LogSink):
    def __init__(
        self,
        topic: str,
        *,
        bootstrap_servers: Optional[str] = None,
        compression: str = "snappy",
        producer_overrides: Optional[dict] = None,
    ) -> None:
        self.topic = topic
        self._log = logging.getLogger("structured_logging.sink.kafka_json")
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
            # Pin api_version: kafka-python-ng's auto-detection can fail
            # against modern brokers (raises UnrecognizedBrokerVersion).
            # 2.5.0 is a safe baseline that all brokers Spark/Confluent ship
            # in the last 5+ years support.
            api_version=(2, 5, 0),
        )
        if producer_overrides:
            cfg.update(producer_overrides)
        self.producer = KafkaProducer(**cfg)

    def name(self) -> str:
        return f"kafka_json[{self.topic}]"

    def publish(self, env: LogEnvelope, callback: SinkCallback = None) -> None:
        try:
            future = self.producer.send(
                self.topic,
                key=env.key.encode("utf-8") if env.key else None,
                value=envelope_to_json_bytes(env),
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
