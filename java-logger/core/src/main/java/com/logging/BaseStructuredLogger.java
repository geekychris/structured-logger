package com.logging;

import com.logging.config.LoggerConfig;
import com.logging.config.SinkFactory;
import com.logging.sink.KafkaSink;
import com.logging.sink.LogEnvelope;
import com.logging.sink.LogSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.function.BiConsumer;

/**
 * Base class for structured logging. Each subclass corresponds to one log
 * schema (topic + log_type + version) and forwards records to a pluggable
 * {@link LogSink}.
 *
 * <p>Three constructor styles are supported:
 *
 * <ol>
 *   <li>Legacy Kafka-only constructors — preserved for backwards compatibility
 *       so the existing generated loggers keep compiling unmodified. These
 *       build a single {@link KafkaSink}.</li>
 *   <li>{@link #BaseStructuredLogger(String, String, String, String, LoggerConfig)} —
 *       loads the configured sink mix (Kafka, SLF4J, file, NATS, composite, ...)
 *       from a {@link LoggerConfig}, typically via {@code LoggerConfig.fromEnvironment()}.</li>
 *   <li>{@link #BaseStructuredLogger(String, String, String, String, LogSink)} —
 *       direct injection of a sink, mainly for tests.</li>
 * </ol>
 */
public abstract class BaseStructuredLogger implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(BaseStructuredLogger.class);

    protected final String topicName;
    private final String loggerName;
    private final String logType;
    private final String version;
    private final LogSink sink;
    private final boolean ownsSink;

    /** Legacy: explicit Kafka bootstrap. Builds a single KafkaSink. */
    protected BaseStructuredLogger(String topicName, String loggerName, String logType, String version,
                                   String kafkaBootstrapServers) {
        this(topicName, loggerName, logType, version,
                new KafkaSink(topicName, kafkaBootstrapServers), true);
    }

    /**
     * Legacy: Kafka bootstrap from env var KAFKA_BOOTSTRAP_SERVERS, unless
     * STRUCTURED_LOG_SINKS is set — in which case the declared sink mix is used.
     */
    protected BaseStructuredLogger(String topicName, String loggerName, String logType, String version) {
        this(topicName, loggerName, logType, version,
                resolveSinkFromEnvironment(topicName, logType, loggerName), true);
    }

    /** Build sinks declaratively from a {@link LoggerConfig}. */
    protected BaseStructuredLogger(String topicName, String loggerName, String logType, String version,
                                   LoggerConfig config) {
        this(topicName, loggerName, logType, version,
                SinkFactory.build(config,
                        new SinkFactory.LoggerContext(topicName, logType, fileBaseFor(loggerName))),
                true);
    }

    /**
     * Build sinks from {@link LoggerConfig} with field metadata. AvroKafkaSink
     * and S3BatchSink need fields to derive their schemas — generated loggers
     * prefer this constructor over the field-less variant.
     */
    protected BaseStructuredLogger(String topicName, String loggerName, String logType, String version,
                                   LoggerConfig config,
                                   java.util.List<java.util.Map<String, Object>> fields) {
        this(topicName, loggerName, logType, version,
                SinkFactory.build(config,
                        new SinkFactory.LoggerContext(topicName, logType, loggerName,
                                fileBaseFor(loggerName), fields)),
                true);
    }

    /**
     * Like the no-config constructor but with field metadata so Avro/S3 sinks
     * can self-configure when STRUCTURED_LOG_SINKS env is set.
     */
    protected BaseStructuredLogger(String topicName, String loggerName, String logType, String version,
                                   java.util.List<java.util.Map<String, Object>> fields) {
        this(topicName, loggerName, logType, version,
                resolveSinkFromEnvironment(topicName, logType, loggerName, fields), true);
    }

    /** Inject a sink directly (tests, custom transports). */
    protected BaseStructuredLogger(String topicName, String loggerName, String logType, String version,
                                   LogSink sink) {
        this(topicName, loggerName, logType, version, sink, false);
    }

    private BaseStructuredLogger(String topicName, String loggerName, String logType, String version,
                                 LogSink sink, boolean ownsSink) {
        this.topicName = topicName;
        this.loggerName = loggerName;
        this.logType = logType;
        this.version = version;
        this.sink = sink;
        this.ownsSink = ownsSink;
        LOG.debug("Initialised logger {} -> sink {}", loggerName, sink.name());
    }

    /**
     * Mixed-deployment heuristic: if STRUCTURED_LOG_SINKS is set, use the
     * declared sink list; otherwise fall back to the legacy single-Kafka behaviour.
     */
    private static LogSink resolveSinkFromEnvironment(String topicName, String logType, String loggerName) {
        return resolveSinkFromEnvironment(topicName, logType, loggerName, java.util.Collections.emptyList());
    }

    private static LogSink resolveSinkFromEnvironment(String topicName, String logType, String loggerName,
                                                      java.util.List<java.util.Map<String, Object>> fields) {
        String declared = System.getenv("STRUCTURED_LOG_SINKS");
        if (declared == null) declared = System.getProperty("STRUCTURED_LOG_SINKS");
        if (declared != null && !declared.isBlank()) {
            LoggerConfig config = LoggerConfig.fromEnvironment();
            return SinkFactory.build(config,
                    new SinkFactory.LoggerContext(topicName, logType, loggerName,
                            fileBaseFor(loggerName), fields));
        }
        String bootstrap = System.getenv("KAFKA_BOOTSTRAP_SERVERS");
        if (bootstrap == null || bootstrap.isBlank()) bootstrap = "localhost:9092";
        return new KafkaSink(topicName, bootstrap);
    }

    private static String fileBaseFor(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]+", "_");
    }

    /** Publish an envelope-wrapped record. */
    protected void publish(String key, Object logRecord) {
        publish(key, logRecord, null);
    }

    /** Publish with a delivery callback. */
    protected void publish(String key, Object logRecord, BiConsumer<Boolean, Exception> callback) {
        LogEnvelope envelope = new LogEnvelope(key, logType, loggerName, version, logRecord);
        sink.publish(envelope, (ok, err) -> {
            if (callback == null) {
                if (Boolean.FALSE.equals(ok)) {
                    LOG.error("Sink {} failed to publish {} record", sink.name(), loggerName, err);
                }
                return;
            }
            Exception asException = err == null
                    ? null
                    : (err instanceof Exception ? (Exception) err : new RuntimeException(err));
            callback.accept(ok, asException);
        });
    }

    /** Helper kept for compatibility with existing generated code. */
    protected Instant now() {
        return Instant.now();
    }

    /** Block until buffered records have been handed to the transport. */
    public void flush() {
        sink.flush();
    }

    @Override
    public void close() {
        try {
            sink.flush();
        } catch (Throwable t) {
            LOG.warn("Sink {} flush during close failed", sink.name(), t);
        }
        if (ownsSink) {
            try {
                sink.close();
            } catch (Throwable t) {
                LOG.warn("Sink {} close failed", sink.name(), t);
            }
        }
        LOG.info("Closed {} logger", loggerName);
    }

    /** Visible for tests. */
    public LogSink sink() {
        return sink;
    }
}
