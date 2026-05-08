package com.logging.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolved logging configuration: which sinks to enable, and the connection
 * settings each sink needs. Built either programmatically (the {@link Builder})
 * or hydrated from environment / system properties via {@link #fromEnvironment()}.
 *
 * Environment variable contract:
 *
 * <pre>
 *   STRUCTURED_LOG_SINKS=kafka,slf4j      # comma-separated SinkType names
 *   KAFKA_BOOTSTRAP_SERVERS=broker:9092   # used by Kafka sink
 *   STRUCTURED_LOG_FILE_DIR=/var/log/app  # used by File sink (one file per logType)
 *   STRUCTURED_LOG_FILE_ROTATE_BYTES=...  # optional
 *   NATS_URL=nats://127.0.0.1:4222        # used by NATS sink
 *   NATS_SUBJECT_PREFIX=logs              # used by NATS sink (default: "logs")
 *   STRUCTURED_LOG_SLF4J_LOGGER=...       # optional, defaults to "structured-logs"
 *   STRUCTURED_LOG_SLF4J_LEVEL=INFO       # optional
 * </pre>
 *
 * The legacy single-sink Kafka behaviour is preserved when no env vars are
 * set and an explicit {@code kafkaBootstrapServers} is provided to a logger
 * constructor.
 */
public final class LoggerConfig {

    private final Set<SinkType> sinks;
    private final String kafkaBootstrapServers;
    private final Path fileDir;
    private final long fileRotateBytes;
    private final boolean fileFsyncOnFlush;
    private final String natsUrl;
    private final String natsSubjectPrefix;
    private final String slf4jLogger;
    private final String slf4jLevel;
    private final Map<String, String> extras;

    private LoggerConfig(Builder b) {
        this.sinks = b.sinks.isEmpty() ? EnumSet.of(SinkType.KAFKA) : EnumSet.copyOf(b.sinks);
        this.kafkaBootstrapServers = b.kafkaBootstrapServers;
        this.fileDir = b.fileDir;
        this.fileRotateBytes = b.fileRotateBytes;
        this.fileFsyncOnFlush = b.fileFsyncOnFlush;
        this.natsUrl = b.natsUrl;
        this.natsSubjectPrefix = b.natsSubjectPrefix;
        this.slf4jLogger = b.slf4jLogger;
        this.slf4jLevel = b.slf4jLevel;
        this.extras = Collections.unmodifiableMap(new LinkedHashMap<>(b.extras));
    }

    public Set<SinkType> sinks() { return sinks; }
    public String kafkaBootstrapServers() { return kafkaBootstrapServers; }
    public Path fileDir() { return fileDir; }
    public long fileRotateBytes() { return fileRotateBytes; }
    public boolean fileFsyncOnFlush() { return fileFsyncOnFlush; }
    public String natsUrl() { return natsUrl; }
    public String natsSubjectPrefix() { return natsSubjectPrefix; }
    public String slf4jLogger() { return slf4jLogger; }
    public String slf4jLevel() { return slf4jLevel; }
    public Map<String, String> extras() { return extras; }

    public static Builder builder() {
        return new Builder();
    }

    /** Read configuration from process environment + system properties. */
    public static LoggerConfig fromEnvironment() {
        return fromMap(snapshotEnvironment());
    }

    /** Visible for tests — accepts any string source. */
    public static LoggerConfig fromMap(Map<String, String> source) {
        Builder b = new Builder();
        String sinks = source.get("STRUCTURED_LOG_SINKS");
        if (sinks != null && !sinks.isBlank()) {
            List<SinkType> parsed = new ArrayList<>();
            for (String tok : sinks.split(",")) {
                if (!tok.trim().isEmpty()) parsed.add(SinkType.parse(tok));
            }
            b.sinks(parsed.toArray(new SinkType[0]));
        }
        String kafka = source.getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        b.kafkaBootstrapServers(kafka);

        String fileDir = source.get("STRUCTURED_LOG_FILE_DIR");
        if (fileDir != null && !fileDir.isBlank()) {
            b.fileDir(Paths.get(fileDir));
        }
        String rotate = source.get("STRUCTURED_LOG_FILE_ROTATE_BYTES");
        if (rotate != null && !rotate.isBlank()) {
            b.fileRotateBytes(Long.parseLong(rotate.trim()));
        }
        String fsync = source.get("STRUCTURED_LOG_FILE_FSYNC");
        if (fsync != null) {
            b.fileFsyncOnFlush(Boolean.parseBoolean(fsync.trim()));
        }
        String natsUrl = source.get("NATS_URL");
        if (natsUrl != null && !natsUrl.isBlank()) {
            b.natsUrl(natsUrl);
        }
        b.natsSubjectPrefix(source.getOrDefault("NATS_SUBJECT_PREFIX", "logs"));
        b.slf4jLogger(source.getOrDefault("STRUCTURED_LOG_SLF4J_LOGGER", "structured-logs"));
        b.slf4jLevel(source.getOrDefault("STRUCTURED_LOG_SLF4J_LEVEL", "INFO"));

        for (Map.Entry<String, String> e : source.entrySet()) {
            if (e.getKey().startsWith("STRUCTURED_LOG_EXTRA_")) {
                b.extra(e.getKey().substring("STRUCTURED_LOG_EXTRA_".length()), e.getValue());
            }
        }
        return b.build();
    }

    private static Map<String, String> snapshotEnvironment() {
        Map<String, String> map = new LinkedHashMap<>(System.getenv());
        for (String key : System.getProperties().stringPropertyNames()) {
            map.putIfAbsent(key, System.getProperty(key));
        }
        return map;
    }

    public static final class Builder {
        private final Set<SinkType> sinks = EnumSet.noneOf(SinkType.class);
        private String kafkaBootstrapServers = "localhost:9092";
        private Path fileDir;
        private long fileRotateBytes = 64L * 1024L * 1024L;
        private boolean fileFsyncOnFlush;
        private String natsUrl;
        private String natsSubjectPrefix = "logs";
        private String slf4jLogger = "structured-logs";
        private String slf4jLevel = "INFO";
        private final Map<String, String> extras = new LinkedHashMap<>();

        public Builder sinks(SinkType... sinks) {
            this.sinks.clear();
            this.sinks.addAll(Arrays.asList(sinks));
            return this;
        }

        public Builder addSink(SinkType sink) { this.sinks.add(sink); return this; }
        public Builder kafkaBootstrapServers(String v) { this.kafkaBootstrapServers = v; return this; }
        public Builder fileDir(Path v) { this.fileDir = v; return this; }
        public Builder fileRotateBytes(long v) { this.fileRotateBytes = v; return this; }
        public Builder fileFsyncOnFlush(boolean v) { this.fileFsyncOnFlush = v; return this; }
        public Builder natsUrl(String v) { this.natsUrl = v; return this; }
        public Builder natsSubjectPrefix(String v) { this.natsSubjectPrefix = v; return this; }
        public Builder slf4jLogger(String v) { this.slf4jLogger = v; return this; }
        public Builder slf4jLevel(String v) { this.slf4jLevel = v; return this; }
        public Builder extra(String k, String v) { this.extras.put(k, v); return this; }

        public LoggerConfig build() {
            return new LoggerConfig(this);
        }
    }
}
