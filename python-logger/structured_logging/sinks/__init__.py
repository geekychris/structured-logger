"""Pluggable sinks for the structured logger.

Mirrors the Java side's `LogSink` interface and `SinkFactory`. Sinks are
selected per-logger via the JSON config's `transport` block, with env-var
overrides for operations.
"""
from .base import LogEnvelope, LogSink, NullSink, CompositeSink
from .file_sink import FileSink
from .kafka_json import KafkaJsonSink
from .factory import build_sink

__all__ = [
    "LogEnvelope",
    "LogSink",
    "NullSink",
    "CompositeSink",
    "FileSink",
    "KafkaJsonSink",
    "build_sink",
]

# KafkaAvroSink + S3BatchSink are heavier (Schema-Registry / boto3 / pyarrow).
# Import lazily to avoid forcing those deps on users who only need JSON+Kafka.
def __getattr__(name):
    if name == "KafkaAvroSink":
        from .kafka_avro import KafkaAvroSink
        return KafkaAvroSink
    if name == "S3BatchSink":
        from .s3_sink import S3BatchSink
        return S3BatchSink
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
