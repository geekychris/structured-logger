package com.logging.config;

import java.util.Locale;

/**
 * Catalogue of sinks the {@link LoggerConfig} factory understands. Operators
 * select a comma-separated list of these in the {@code STRUCTURED_LOG_SINKS}
 * env var (or equivalent system property) to compose a delivery pipeline at
 * deploy time without recompiling.
 */
public enum SinkType {
    /** Discards records. */
    NULL,
    /** Routes records through SLF4J. */
    SLF4J,
    /** Appends NDJSON to a local file (intended to be tailed by the sidecar). */
    FILE,
    /** Publishes to a Kafka topic. */
    KAFKA,
    /** Publishes to a NATS JetStream subject. */
    NATS,
    /** Batches envelopes in memory and flushes to S3 as Avro+Snappy or Parquet+Zstd objects. */
    S3;

    public static SinkType parse(String raw) {
        if (raw == null) throw new IllegalArgumentException("Sink type may not be null");
        return SinkType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }
}
