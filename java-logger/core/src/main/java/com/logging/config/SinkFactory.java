package com.logging.config;

import com.logging.sink.CompositeSink;
import com.logging.sink.FileSink;
import com.logging.sink.KafkaSink;
import com.logging.sink.LogSink;
import com.logging.sink.NullSink;
import com.logging.sink.Slf4jSink;
import org.slf4j.event.Level;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Materialises {@link LogSink}s from a {@link LoggerConfig}. Constructors that
 * need transport-specific arguments (Kafka topic, file path) are supplied by
 * the per-logger context object {@link LoggerContext} so that ops keep their
 * one-config-fits-all view while individual generated loggers still target
 * their own topics / files / subjects.
 */
public final class SinkFactory {

    private SinkFactory() {}

    /** Per-logger metadata required to materialise transport-specific sinks. */
    public static final class LoggerContext {
        public final String topic;       // Kafka topic
        public final String logType;     // becomes part of NATS subject + filename
        public final String fileBase;    // base file name (no extension)

        public LoggerContext(String topic, String logType, String fileBase) {
            this.topic = topic;
            this.logType = logType;
            this.fileBase = fileBase;
        }
    }

    public static LogSink build(LoggerConfig config, LoggerContext ctx) {
        List<LogSink> sinks = new ArrayList<>();
        for (SinkType type : config.sinks()) {
            sinks.add(buildOne(type, config, ctx));
        }
        if (sinks.isEmpty()) {
            return new NullSink();
        }
        if (sinks.size() == 1) {
            return sinks.get(0);
        }
        return new CompositeSink(sinks);
    }

    private static LogSink buildOne(SinkType type, LoggerConfig config, LoggerContext ctx) {
        switch (type) {
            case NULL:
                return new NullSink();
            case SLF4J: {
                Level level;
                try {
                    level = Level.valueOf(config.slf4jLevel());
                } catch (IllegalArgumentException e) {
                    level = Level.INFO;
                }
                return new Slf4jSink(config.slf4jLogger(), level);
            }
            case FILE: {
                Path dir = config.fileDir();
                if (dir == null) {
                    throw new IllegalStateException("FILE sink configured but STRUCTURED_LOG_FILE_DIR / fileDir() is unset");
                }
                Path file = dir.resolve(ctx.fileBase + ".ndjson");
                return new FileSink(file, config.fileRotateBytes(), config.fileFsyncOnFlush());
            }
            case KAFKA: {
                return new KafkaSink(ctx.topic, config.kafkaBootstrapServers());
            }
            case NATS: {
                String url = config.natsUrl();
                if (url == null) {
                    throw new IllegalStateException("NATS sink configured but NATS_URL / natsUrl() is unset");
                }
                return reflectivelyBuildNatsSink(url, config.natsSubjectPrefix());
            }
            default:
                throw new IllegalStateException("Unknown sink type: " + type);
        }
    }

    /**
     * NATS is an optional dependency: build the sink reflectively so that
     * apps which never use NATS aren't forced to put jnats on the classpath.
     */
    private static LogSink reflectivelyBuildNatsSink(String url, String subjectPrefix) {
        try {
            Class<?> clazz = Class.forName("com.logging.sink.NatsJetStreamSink");
            return (LogSink) clazz.getConstructor(String.class, String.class)
                    .newInstance(url, subjectPrefix);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "NATS sink requested but com.logging.sink.NatsJetStreamSink is not on the classpath. " +
                    "Add the io.nats:jnats dependency to your application.", e);
        } catch (NoSuchMethodException | IllegalAccessException | InstantiationException e) {
            throw new IllegalStateException("Failed to construct NatsJetStreamSink", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new IllegalStateException("Failed to construct NatsJetStreamSink", cause);
        }
    }
}
