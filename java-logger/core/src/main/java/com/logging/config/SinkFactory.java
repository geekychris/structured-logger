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
import java.util.Collections;
import java.util.List;
import java.util.Map;

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
        public final String logClass;    // used to derive Avro record name
        public final String fileBase;    // base file name (no extension)
        public final List<Map<String, Object>> fields;  // for Avro / Parquet schema derivation

        /** Legacy constructor — Avro/S3 sinks won't work without fields/logClass. */
        public LoggerContext(String topic, String logType, String fileBase) {
            this(topic, logType, logType, fileBase, Collections.emptyList());
        }

        public LoggerContext(String topic, String logType, String logClass,
                             String fileBase, List<Map<String, Object>> fields) {
            this.topic = topic;
            this.logType = logType;
            this.logClass = logClass;
            this.fileBase = fileBase;
            this.fields = fields == null ? Collections.emptyList() : fields;
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
                if ("avro".equalsIgnoreCase(config.kafkaEncoding())) {
                    if (config.schemaRegistryUrl() == null) {
                        throw new IllegalStateException(
                                "kafka encoding=avro requires schemaRegistryUrl / SCHEMA_REGISTRY_URL");
                    }
                    return reflectivelyBuildAvroKafkaSink(
                            ctx.topic, ctx.logClass, ctx.fields,
                            config.schemaRegistryUrl(), config.kafkaBootstrapServers());
                }
                return new KafkaSink(ctx.topic, config.kafkaBootstrapServers());
            }
            case NATS: {
                String url = config.natsUrl();
                if (url == null) {
                    throw new IllegalStateException("NATS sink configured but NATS_URL / natsUrl() is unset");
                }
                return reflectivelyBuildNatsSink(url, config.natsSubjectPrefix());
            }
            case S3: {
                if (config.s3Bucket() == null) {
                    throw new IllegalStateException(
                            "S3 sink configured but s3Bucket / STRUCTURED_LOG_S3_BUCKET is unset");
                }
                if (ctx.fields.isEmpty()) {
                    throw new IllegalStateException(
                            "S3 sink requires field list in LoggerContext to derive an Avro/Parquet schema");
                }
                return reflectivelyBuildS3BatchSink(config, ctx);
            }
            default:
                throw new IllegalStateException("Unknown sink type: " + type);
        }
    }

    /**
     * AvroKafkaSink uses Apache Avro reflectively — Avro is an optional dep,
     * so apps that only use JSON Kafka don't need it on the classpath.
     */
    @SuppressWarnings("unchecked")
    private static LogSink reflectivelyBuildAvroKafkaSink(
            String topic, String logClass, List<Map<String, Object>> fields,
            String schemaRegistryUrl, String bootstrapServers) {
        try {
            Class<?> clazz = Class.forName("com.logging.sink.AvroKafkaSink");
            return (LogSink) clazz.getConstructor(String.class, String.class, List.class, String.class, String.class)
                    .newInstance(topic, logClass, fields, schemaRegistryUrl, bootstrapServers);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "Avro Kafka sink requested but classpath is missing org.apache.avro:avro. " +
                    "Add the Avro dependency to your application.", e);
        } catch (NoSuchMethodException | IllegalAccessException | InstantiationException e) {
            throw new IllegalStateException("Failed to construct AvroKafkaSink", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new IllegalStateException("Failed to construct AvroKafkaSink", cause);
        }
    }

    /**
     * S3BatchSink is built reflectively — its dependencies (avro, parquet-avro,
     * hadoop-common, aws-sdk:s3) are optional and only needed if the operator
     * actually selects the S3 sink.
     */
    private static LogSink reflectivelyBuildS3BatchSink(LoggerConfig config, LoggerContext ctx) {
        try {
            Class<?> cfgClazz = Class.forName("com.logging.sink.S3BatchSink$Config");
            Object cfg = cfgClazz.getConstructor().newInstance();
            cfgClazz.getMethod("bucket", String.class).invoke(cfg, config.s3Bucket());
            cfgClazz.getMethod("endpoint", String.class).invoke(cfg, config.s3Endpoint());
            cfgClazz.getMethod("region", String.class).invoke(cfg, config.s3Region());
            cfgClazz.getMethod("pathStyle", boolean.class).invoke(cfg, config.s3PathStyle());
            cfgClazz.getMethod("rotateSeconds", int.class).invoke(cfg, config.s3RotateSeconds());
            cfgClazz.getMethod("rotateBytes", long.class).invoke(cfg, config.s3RotateBytes());
            cfgClazz.getMethod("maxRecords", int.class).invoke(cfg, config.s3MaxRecords());
            cfgClazz.getMethod("keyPrefix", String.class).invoke(cfg, config.s3KeyPrefix());
            // encoding enum
            Class<?> encClazz = Class.forName("com.logging.sink.S3BatchSink$Encoding");
            Object encVal = Enum.valueOf((Class<Enum>) encClazz, config.s3Encoding().toUpperCase());
            cfgClazz.getMethod("encoding", encClazz).invoke(cfg, encVal);
            cfgClazz.getMethod("avroSchemaFromConfig", String.class, List.class)
                    .invoke(cfg, ctx.logClass, ctx.fields);
            // credentials from env if set
            String ak = System.getenv("AWS_ACCESS_KEY_ID");
            String sk = System.getenv("AWS_SECRET_ACCESS_KEY");
            if (ak != null && sk != null) {
                cfgClazz.getMethod("credentials", String.class, String.class).invoke(cfg, ak, sk);
            }

            Class<?> sinkClazz = Class.forName("com.logging.sink.S3BatchSink");
            return (LogSink) sinkClazz.getConstructor(cfgClazz).newInstance(cfg);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "S3 sink requested but classpath is missing avro / parquet-avro / hadoop-common / aws-sdk:s3.", e);
        } catch (NoSuchMethodException | IllegalAccessException | InstantiationException e) {
            throw new IllegalStateException("Failed to construct S3BatchSink", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new IllegalStateException("Failed to construct S3BatchSink", cause);
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
