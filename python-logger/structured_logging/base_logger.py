"""Base structured logger for Kafka publishing."""

import json
import logging
import os
from datetime import datetime, date
from typing import Any, Dict, Optional, Callable
from kafka import KafkaProducer
from kafka.errors import KafkaError


class BaseStructuredLogger:
    """
    Base class for structured logging to Kafka.
    Provides common functionality for serialization and publishing.
    """

    def __init__(
        self,
        topic_name: str,
        logger_name: str,
        kafka_bootstrap_servers: Optional[str] = None,
    ):
        """
        Initialize the base structured logger.

        Args:
            topic_name: Kafka topic to publish to
            logger_name: Name of this logger for identification
            kafka_bootstrap_servers: Kafka bootstrap servers (defaults to env var or localhost)
        """
        self.topic_name = topic_name
        self.logger_name = logger_name
        self.logger = logging.getLogger(f"structured_logging.{logger_name}")

        bootstrap_servers = kafka_bootstrap_servers or os.getenv(
            "KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"
        )

        self.producer = KafkaProducer(
            bootstrap_servers=bootstrap_servers,
            value_serializer=self._serialize_json,
            compression_type=None,
            acks=1,
            retries=3,
            linger_ms=10,
            batch_size=32768,
        )

    def _serialize_json(self, obj: Any) -> bytes:
        """Serialize object to JSON bytes with datetime handling."""
        return json.dumps(obj, default=self._json_default).encode("utf-8")

    @staticmethod
    def _json_default(obj: Any) -> Any:
        """Handle datetime serialization for JSON."""
        if isinstance(obj, datetime):
            return obj.isoformat()
        elif isinstance(obj, date):
            return obj.isoformat()
        raise TypeError(f"Object of type {type(obj)} is not JSON serializable")

    def publish(
        self,
        key: str,
        log_record: Dict[str, Any],
        callback: Optional[Callable[[bool, Optional[Exception]], None]] = None,
    ) -> None:
        """
        Publish a log record to Kafka.

        Args:
            key: Partition key (typically user_id or similar)
            log_record: The log record dictionary to publish
            callback: Optional callback function for async notification
        """
        try:
            future = self.producer.send(
                self.topic_name, key=key.encode("utf-8"), value=log_record
            )

            if callback:
                future.add_callback(
                    lambda metadata: self._on_send_success(metadata, callback)
                ).add_errback(lambda exc: self._on_send_error(exc, callback))
            else:
                future.add_callback(self._on_send_success).add_errback(
                    self._on_send_error
                )

        except Exception as e:
            self.logger.error(f"Error publishing {self.logger_name} log record: {e}")
            if callback:
                callback(False, e)

    def _on_send_success(self, metadata, callback=None):
        """Handle successful send."""
        self.logger.debug(
            f"Published {self.logger_name} log record to topic {self.topic_name} "
            f"partition {metadata.partition} offset {metadata.offset}"
        )
        if callback:
            callback(True, None)

    def _on_send_error(self, exc, callback=None):
        """Handle send error."""
        self.logger.error(
            f"Failed to publish {self.logger_name} log record to topic {self.topic_name}: {exc}"
        )
        if callback:
            callback(False, exc)

    @staticmethod
    def now() -> datetime:
        """Get current timestamp."""
        return datetime.utcnow()

    def flush(self) -> None:
        """Flush all pending messages."""
        self.producer.flush()

    def close(self) -> None:
        """Flush pending messages and close the producer."""
        try:
            self.producer.flush()
            self.producer.close(timeout=5)
            self.logger.info(f"Closed {self.logger_name} logger")
        except Exception as e:
            self.logger.error(f"Error closing {self.logger_name} logger: {e}")

    def __enter__(self):
        """Context manager entry."""
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        """Context manager exit."""
        self.close()
