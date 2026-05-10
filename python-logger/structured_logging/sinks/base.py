"""Sink interface + envelope shape + composition primitives."""
from __future__ import annotations

import json
import logging
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import date, datetime
from typing import Any, Callable, Dict, List, Optional


@dataclass
class LogEnvelope:
    """Wire envelope. Mirrors the Java LogEnvelope so the Spark consumer
    sees the same shape from every producer."""

    log_type: str
    log_class: str
    version: str
    data: Dict[str, Any]
    key: str = ""

    def to_dict(self) -> Dict[str, Any]:
        return {
            "_log_type": self.log_type,
            "_log_class": self.log_class,
            "_version": self.version,
            "data": self.data,
        }


def _json_default(obj: Any) -> Any:
    if isinstance(obj, (datetime, date)):
        return obj.isoformat()
    raise TypeError(f"Object of type {type(obj)} is not JSON serializable")


def envelope_to_json_bytes(env: LogEnvelope) -> bytes:
    return json.dumps(env.to_dict(), default=_json_default).encode("utf-8")


# Callback signature: (success: bool, error: Optional[BaseException]) -> None
SinkCallback = Optional[Callable[[bool, Optional[BaseException]], None]]


class LogSink(ABC):
    """Pluggable transport. Implementations must be thread-safe and reusable
    across loggers (multiple generated loggers may share one sink instance)."""

    @abstractmethod
    def name(self) -> str: ...

    @abstractmethod
    def publish(self, env: LogEnvelope, callback: SinkCallback = None) -> None:
        """Publish one envelope. Returns immediately; callback fires once."""

    def flush(self, timeout_s: float = 30.0) -> None:
        """Block until all pending records are durable."""
        return None

    def close(self) -> None:
        """Flush + release resources. Idempotent."""
        return None

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        self.close()


class NullSink(LogSink):
    """Discards records. Default fallback when no sinks configured."""

    def name(self) -> str:
        return "null"

    def publish(self, env: LogEnvelope, callback: SinkCallback = None) -> None:
        if callback:
            callback(True, None)


@dataclass
class CompositeSink(LogSink):
    """Fans a single publish out to multiple sinks. Calls back once with
    aggregate status (true iff all children succeeded)."""

    children: List[LogSink] = field(default_factory=list)
    _logger: logging.Logger = field(default_factory=lambda: logging.getLogger("structured_logging.composite"))

    def name(self) -> str:
        return "composite(" + "+".join(c.name() for c in self.children) + ")"

    def publish(self, env: LogEnvelope, callback: SinkCallback = None) -> None:
        if not self.children:
            if callback:
                callback(True, None)
            return
        n = len(self.children)
        state = {"done": 0, "ok": True, "err": None}

        def child_cb(ok: bool, err: Optional[BaseException]) -> None:
            state["done"] += 1
            if not ok:
                state["ok"] = False
                state["err"] = state["err"] or err
            if state["done"] == n and callback:
                callback(state["ok"], state["err"])

        for c in self.children:
            try:
                c.publish(env, child_cb)
            except Exception as e:  # noqa: BLE001 -- we want broad catch here
                self._logger.exception("sink %s threw on publish", c.name())
                child_cb(False, e)

    def flush(self, timeout_s: float = 30.0) -> None:
        for c in self.children:
            try:
                c.flush(timeout_s)
            except Exception:  # noqa: BLE001
                self._logger.exception("sink %s threw on flush", c.name())

    def close(self) -> None:
        for c in self.children:
            try:
                c.close()
            except Exception:  # noqa: BLE001
                self._logger.exception("sink %s threw on close", c.name())
