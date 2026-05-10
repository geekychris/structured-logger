"""NDJSON FileSink. One envelope per line. Size-based rotation.
Designed to be tailed by a sidecar (S3BatchSink, kafka-tailer, etc.)."""
from __future__ import annotations

import logging
import os
import threading
from pathlib import Path
from typing import Optional

from .base import LogEnvelope, LogSink, SinkCallback, envelope_to_json_bytes


class FileSink(LogSink):
    def __init__(
        self,
        active_path: os.PathLike,
        *,
        rotate_bytes: int = 64 * 1024 * 1024,
        fsync_on_flush: bool = False,
    ) -> None:
        self.active = Path(active_path)
        self.active.parent.mkdir(parents=True, exist_ok=True)
        self.rotate_bytes = rotate_bytes
        self.fsync_on_flush = fsync_on_flush
        self._lock = threading.Lock()
        self._fh = open(self.active, "ab", buffering=0)
        self._bytes = self.active.stat().st_size
        self._rotation_idx = 0
        self._log = logging.getLogger("structured_logging.sink.file")

    def name(self) -> str:
        return f"file[{self.active}]"

    def publish(self, env: LogEnvelope, callback: SinkCallback = None) -> None:
        line = envelope_to_json_bytes(env) + b"\n"
        try:
            with self._lock:
                self._fh.write(line)
                self._bytes += len(line)
                if self._bytes >= self.rotate_bytes:
                    self._rotate_locked()
        except Exception as e:  # noqa: BLE001
            self._log.error("file write threw: %s", e)
            if callback:
                callback(False, e)
            return
        if callback:
            callback(True, None)

    def _rotate_locked(self) -> None:
        try:
            self._fh.close()
        except Exception:
            pass
        self._rotation_idx += 1
        rotated = Path(str(self.active) + f".{self._rotation_idx}")
        try:
            self.active.rename(rotated)
        except Exception as e:  # noqa: BLE001
            self._log.warning("rename %s -> %s failed: %s", self.active, rotated, e)
        self._fh = open(self.active, "ab", buffering=0)
        self._bytes = 0

    def flush(self, timeout_s: float = 30.0) -> None:
        with self._lock:
            try:
                self._fh.flush()
                if self.fsync_on_flush:
                    os.fsync(self._fh.fileno())
            except Exception:
                pass

    def close(self) -> None:
        with self._lock:
            try:
                self._fh.flush()
            finally:
                self._fh.close()
