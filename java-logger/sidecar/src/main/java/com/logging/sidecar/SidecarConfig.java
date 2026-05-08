package com.logging.sidecar;

import com.logging.config.SinkType;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;

/**
 * Sidecar runtime configuration. Designed to be hydrated from environment
 * variables in both kubernetes (where the sidecar is a separate container in
 * the same pod, sharing an emptyDir volume with the app) and standalone
 * deployments (a system service tailing /var/log/app).
 *
 * <pre>
 *   SIDECAR_WATCH_DIR=/var/log/app           # directory to tail (required)
 *   SIDECAR_FILE_GLOB=*.ndjson               # which files to tail
 *   SIDECAR_CHECKPOINT_FILE=/var/lib/sidecar/positions.json
 *   SIDECAR_POLL_INTERVAL_MS=500
 *   SIDECAR_TARGET=KAFKA|NATS|FILE           # downstream sink
 *   SIDECAR_TARGET_TOPIC=logs.shared         # for KAFKA
 *   KAFKA_BOOTSTRAP_SERVERS=broker:9092
 *   NATS_URL=nats://nats:4222
 *   NATS_SUBJECT_PREFIX=logs                 # for NATS (default: logs)
 *   SIDECAR_TARGET_FILE_DIR=/var/log/shipped # for FILE
 * </pre>
 */
public final class SidecarConfig {

    private final Path watchDir;
    private final String fileGlob;
    private final Path checkpointFile;
    private final long pollIntervalMs;
    private final SinkType target;
    private final String targetTopic;
    private final String kafkaBootstrap;
    private final String natsUrl;
    private final String natsSubjectPrefix;
    private final Path targetFileDir;

    private SidecarConfig(Builder b) {
        this.watchDir = Objects.requireNonNull(b.watchDir, "watchDir");
        this.fileGlob = b.fileGlob;
        this.checkpointFile = b.checkpointFile;
        this.pollIntervalMs = b.pollIntervalMs;
        this.target = Objects.requireNonNull(b.target, "target");
        this.targetTopic = b.targetTopic;
        this.kafkaBootstrap = b.kafkaBootstrap;
        this.natsUrl = b.natsUrl;
        this.natsSubjectPrefix = b.natsSubjectPrefix;
        this.targetFileDir = b.targetFileDir;
    }

    public Path watchDir() { return watchDir; }
    public String fileGlob() { return fileGlob; }
    public Path checkpointFile() { return checkpointFile; }
    public long pollIntervalMs() { return pollIntervalMs; }
    public SinkType target() { return target; }
    public String targetTopic() { return targetTopic; }
    public String kafkaBootstrap() { return kafkaBootstrap; }
    public String natsUrl() { return natsUrl; }
    public String natsSubjectPrefix() { return natsSubjectPrefix; }
    public Path targetFileDir() { return targetFileDir; }

    public static SidecarConfig fromEnvironment() {
        return fromMap(System.getenv());
    }

    public static SidecarConfig fromMap(Map<String, String> env) {
        Builder b = new Builder();
        String watchDir = env.get("SIDECAR_WATCH_DIR");
        if (watchDir == null) throw new IllegalStateException("SIDECAR_WATCH_DIR is required");
        b.watchDir(Paths.get(watchDir));
        b.fileGlob(env.getOrDefault("SIDECAR_FILE_GLOB", "*.ndjson"));
        String cp = env.getOrDefault("SIDECAR_CHECKPOINT_FILE", watchDir + "/.sidecar-positions.json");
        b.checkpointFile(Paths.get(cp));
        b.pollIntervalMs(Long.parseLong(env.getOrDefault("SIDECAR_POLL_INTERVAL_MS", "500")));
        b.target(SinkType.parse(env.getOrDefault("SIDECAR_TARGET", "KAFKA")));
        b.targetTopic(env.getOrDefault("SIDECAR_TARGET_TOPIC", "logs.shared"));
        b.kafkaBootstrap(env.getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"));
        b.natsUrl(env.getOrDefault("NATS_URL", "nats://127.0.0.1:4222"));
        b.natsSubjectPrefix(env.getOrDefault("NATS_SUBJECT_PREFIX", "logs"));
        String fileDir = env.get("SIDECAR_TARGET_FILE_DIR");
        if (fileDir != null) b.targetFileDir(Paths.get(fileDir));
        return b.build();
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Path watchDir;
        private String fileGlob = "*.ndjson";
        private Path checkpointFile;
        private long pollIntervalMs = 500;
        private SinkType target = SinkType.KAFKA;
        private String targetTopic = "logs.shared";
        private String kafkaBootstrap = "localhost:9092";
        private String natsUrl = "nats://127.0.0.1:4222";
        private String natsSubjectPrefix = "logs";
        private Path targetFileDir;

        public Builder watchDir(Path v) { this.watchDir = v; return this; }
        public Builder fileGlob(String v) { this.fileGlob = v; return this; }
        public Builder checkpointFile(Path v) { this.checkpointFile = v; return this; }
        public Builder pollIntervalMs(long v) { this.pollIntervalMs = v; return this; }
        public Builder target(SinkType v) { this.target = v; return this; }
        public Builder targetTopic(String v) { this.targetTopic = v; return this; }
        public Builder kafkaBootstrap(String v) { this.kafkaBootstrap = v; return this; }
        public Builder natsUrl(String v) { this.natsUrl = v; return this; }
        public Builder natsSubjectPrefix(String v) { this.natsSubjectPrefix = v; return this; }
        public Builder targetFileDir(Path v) { this.targetFileDir = v; return this; }

        public SidecarConfig build() { return new SidecarConfig(this); }
    }
}
