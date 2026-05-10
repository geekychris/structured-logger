package com.logging.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    // Encoding + Schema Registry (used by AvroKafkaSink)
    private final String kafkaEncoding;            // "json" (default) or "avro"
    private final String schemaRegistryUrl;        // required when kafkaEncoding=avro

    // S3 sink config (used when sinks contains S3)
    private final String s3Bucket;
    private final String s3Endpoint;
    private final String s3Region;
    private final boolean s3PathStyle;
    private final String s3Encoding;               // "avro" or "parquet"
    private final int s3RotateSeconds;
    private final long s3RotateBytes;
    private final int s3MaxRecords;
    private final String s3KeyPrefix;

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
        this.kafkaEncoding = b.kafkaEncoding;
        this.schemaRegistryUrl = b.schemaRegistryUrl;
        this.s3Bucket = b.s3Bucket;
        this.s3Endpoint = b.s3Endpoint;
        this.s3Region = b.s3Region;
        this.s3PathStyle = b.s3PathStyle;
        this.s3Encoding = b.s3Encoding;
        this.s3RotateSeconds = b.s3RotateSeconds;
        this.s3RotateBytes = b.s3RotateBytes;
        this.s3MaxRecords = b.s3MaxRecords;
        this.s3KeyPrefix = b.s3KeyPrefix;
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
    public String kafkaEncoding() { return kafkaEncoding; }
    public String schemaRegistryUrl() { return schemaRegistryUrl; }
    public String s3Bucket() { return s3Bucket; }
    public String s3Endpoint() { return s3Endpoint; }
    public String s3Region() { return s3Region; }
    public boolean s3PathStyle() { return s3PathStyle; }
    public String s3Encoding() { return s3Encoding; }
    public int s3RotateSeconds() { return s3RotateSeconds; }
    public long s3RotateBytes() { return s3RotateBytes; }
    public int s3MaxRecords() { return s3MaxRecords; }
    public String s3KeyPrefix() { return s3KeyPrefix; }

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

        // Encoding + Schema Registry
        String enc = source.get("STRUCTURED_LOG_KAFKA_ENCODING");
        if (enc != null && !enc.isBlank()) b.kafkaEncoding(enc.trim().toLowerCase(Locale.ROOT));
        String srUrl = source.get("SCHEMA_REGISTRY_URL");
        if (srUrl != null && !srUrl.isBlank()) b.schemaRegistryUrl(srUrl.trim());

        // S3 sink
        String s3Bucket = source.get("STRUCTURED_LOG_S3_BUCKET");
        if (s3Bucket != null && !s3Bucket.isBlank()) b.s3Bucket(s3Bucket.trim());
        String s3Endpoint = source.get("STRUCTURED_LOG_S3_ENDPOINT");
        if (s3Endpoint != null && !s3Endpoint.isBlank()) b.s3Endpoint(s3Endpoint.trim());
        String s3Region = source.get("STRUCTURED_LOG_S3_REGION");
        if (s3Region != null && !s3Region.isBlank()) b.s3Region(s3Region.trim());
        String s3PathStyle = source.get("STRUCTURED_LOG_S3_PATH_STYLE");
        if (s3PathStyle != null) b.s3PathStyle(Boolean.parseBoolean(s3PathStyle.trim()));
        String s3Enc = source.get("STRUCTURED_LOG_S3_ENCODING");
        if (s3Enc != null && !s3Enc.isBlank()) b.s3Encoding(s3Enc.trim().toLowerCase(Locale.ROOT));
        String s3Rot = source.get("STRUCTURED_LOG_S3_ROTATE_SECONDS");
        if (s3Rot != null && !s3Rot.isBlank()) b.s3RotateSeconds(Integer.parseInt(s3Rot.trim()));
        String s3RotB = source.get("STRUCTURED_LOG_S3_ROTATE_BYTES");
        if (s3RotB != null && !s3RotB.isBlank()) b.s3RotateBytes(Long.parseLong(s3RotB.trim()));
        String s3Max = source.get("STRUCTURED_LOG_S3_MAX_RECORDS");
        if (s3Max != null && !s3Max.isBlank()) b.s3MaxRecords(Integer.parseInt(s3Max.trim()));
        String s3Prefix = source.get("STRUCTURED_LOG_S3_KEY_PREFIX");
        if (s3Prefix != null && !s3Prefix.isBlank()) b.s3KeyPrefix(s3Prefix.trim());

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

        private String kafkaEncoding = "json";
        private String schemaRegistryUrl;
        private String s3Bucket;
        private String s3Endpoint;
        private String s3Region = "us-east-1";
        private boolean s3PathStyle;
        private String s3Encoding = "parquet";
        private int s3RotateSeconds = 60;
        private long s3RotateBytes = 64L * 1024L * 1024L;
        private int s3MaxRecords = 50_000;
        private String s3KeyPrefix = "";

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
        public Builder kafkaEncoding(String v) { this.kafkaEncoding = v; return this; }
        public Builder schemaRegistryUrl(String v) { this.schemaRegistryUrl = v; return this; }
        public Builder s3Bucket(String v) { this.s3Bucket = v; return this; }
        public Builder s3Endpoint(String v) { this.s3Endpoint = v; return this; }
        public Builder s3Region(String v) { this.s3Region = v; return this; }
        public Builder s3PathStyle(boolean v) { this.s3PathStyle = v; return this; }
        public Builder s3Encoding(String v) { this.s3Encoding = v; return this; }
        public Builder s3RotateSeconds(int v) { this.s3RotateSeconds = v; return this; }
        public Builder s3RotateBytes(long v) { this.s3RotateBytes = v; return this; }
        public Builder s3MaxRecords(int v) { this.s3MaxRecords = v; return this; }
        public Builder s3KeyPrefix(String v) { this.s3KeyPrefix = v; return this; }

        public LoggerConfig build() {
            return new LoggerConfig(this);
        }
    }
}
