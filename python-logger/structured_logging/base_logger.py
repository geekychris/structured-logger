"""Base structured logger with pluggable sinks.

The historical signature `BaseStructuredLogger(topic_name, logger_name, kafka_bootstrap_servers)`
still works — it constructs a default JSON+Snappy Kafka sink, matching the
previous behaviour but with snappy compression turned ON (it was off before).

Generated loggers built with the new generator pass log_type, log_class,
version, fields, and an optional `transport` config to enable sink chains.
"""
from __future__ import annotations

import logging
from datetime import date, datetime
from typing import Any, Callable, Dict, List, Optional

from .sinks import LogEnvelope, LogSink
from .sinks.factory import build_sink


class BaseStructuredLogger:
    """Base class for structured logging via pluggable sinks.

    Constructors:
      Modern:
        BaseStructuredLogger(topic_name, logger_name, log_type=..., log_class=...,
                             version=..., fields=..., transport=...)
        — builds the sink chain via build_sink().

      Legacy (kept for back-compat):
        BaseStructuredLogger(topic_name, logger_name, kafka_bootstrap_servers=None)
        — constructs a default Kafka+JSON+Snappy sink.

      Direct sink injection (for tests):
        BaseStructuredLogger(topic_name, logger_name, sink=my_sink)
    """

    def __init__(
        self,
        topic_name: str,
        logger_name: str,
        kafka_bootstrap_servers: Optional[str] = None,
        *,
        log_type: Optional[str] = None,
        log_class: Optional[str] = None,
        version: str = "1.0.0",
        fields: Optional[List[Dict[str, Any]]] = None,
        transport: Optional[Dict[str, Any]] = None,
        sink: Optional[LogSink] = None,
    ) -> None:
        self.topic_name = topic_name
        self.logger_name = logger_name
        self.log_type = log_type or logger_name.lower()
        self.log_class = log_class or logger_name
        self.version = version
        self.logger = logging.getLogger(f"structured_logging.{logger_name}")

        if sink is not None:
            self._sink: LogSink = sink
        else:
            if kafka_bootstrap_servers:
                import os
                os.environ.setdefault("KAFKA_BOOTSTRAP_SERVERS", kafka_bootstrap_servers)
            self._sink = build_sink(
                log_type=self.log_type,
                log_class=self.log_class,
                version=self.version,
                topic=topic_name,
                fields=fields or [],
                transport=transport,
            )
        self.logger.info("logger %s using sink: %s", self.logger_name, self._sink.name())

    def publish(
        self,
        key: str,
        log_record: Dict[str, Any],
        callback: Optional[Callable[[bool, Optional[BaseException]], None]] = None,
    ) -> None:
        env = LogEnvelope(
            log_type=self.log_type,
            log_class=self.log_class,
            version=self.version,
            data=self._jsonable(log_record),
            key=key or "",
        )
        self._sink.publish(env, callback)

    @staticmethod
    def _jsonable(d: Dict[str, Any]) -> Dict[str, Any]:
        out: Dict[str, Any] = {}
        for k, v in d.items():
            if isinstance(v, (datetime, date)):
                out[k] = v.isoformat()
            else:
                out[k] = v
        return out

    @staticmethod
    def now() -> datetime:
        return datetime.utcnow()

    def flush(self, timeout_s: float = 30.0) -> None:
        self._sink.flush(timeout_s)

    def close(self) -> None:
        try:
            self._sink.close()
            self.logger.info(f"Closed {self.logger_name} logger")
        except Exception as e:  # noqa: BLE001
            self.logger.error(f"Error closing {self.logger_name} logger: {e}")

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()
