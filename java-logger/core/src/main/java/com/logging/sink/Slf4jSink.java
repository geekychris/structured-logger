package com.logging.sink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.util.function.BiConsumer;

/**
 * Routes log envelopes through SLF4J as a single JSON line per record. Lets
 * applications keep using their existing log appenders (console, file, ELK,
 * Datadog agent, etc.) without owning a Kafka producer.
 *
 * Each record is emitted at a configurable level (default INFO) under a
 * configurable logger name (default {@code structured-logs}).
 */
public final class Slf4jSink implements LogSink {

    private final Logger delegate;
    private final Level level;
    private final String name;

    public Slf4jSink() {
        this("structured-logs", Level.INFO);
    }

    public Slf4jSink(String loggerName, Level level) {
        this.delegate = LoggerFactory.getLogger(loggerName);
        this.level = level;
        this.name = "slf4j(" + loggerName + ")";
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void publish(LogEnvelope envelope, BiConsumer<Boolean, Throwable> callback) {
        try {
            String json = EnvelopeSerializer.toJson(envelope);
            switch (level) {
                case ERROR: delegate.error(json); break;
                case WARN:  delegate.warn(json);  break;
                case INFO:  delegate.info(json);  break;
                case DEBUG: delegate.debug(json); break;
                case TRACE: delegate.trace(json); break;
            }
            if (callback != null) callback.accept(true, null);
        } catch (Throwable t) {
            if (callback != null) callback.accept(false, t);
        }
    }

    @Override
    public void flush() {
        // SLF4J does not expose a flush hook; the underlying appender is responsible.
    }

    @Override
    public void close() {
        // The SLF4J factory owns the logger lifecycle.
    }
}
