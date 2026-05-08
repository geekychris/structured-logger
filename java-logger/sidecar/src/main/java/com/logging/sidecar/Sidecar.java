package com.logging.sidecar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logging.config.SinkType;
import com.logging.sink.FileSink;
import com.logging.sink.KafkaSink;
import com.logging.sink.LogEnvelope;
import com.logging.sink.LogSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The deliverable: a process that tails NDJSON files written by application
 * pods (via {@link com.logging.sink.FileSink}) and re-publishes each line
 * through a {@link LogSink} of the operator's choice (Kafka, NATS JetStream,
 * or another file).
 *
 * Why a sidecar? Three reasons:
 *
 * <ul>
 *   <li>Hot-path latency — the app does a buffered local file write and is done.
 *       Network I/O moves out of the request path.</li>
 *   <li>Decoupling — apps don't need a Kafka/NATS client on the classpath, and a
 *       broker outage doesn't backpressure the application.</li>
 *   <li>Pluggable transport — flat-file → object store, Kafka, or NATS without
 *       redeploying the app.</li>
 * </ul>
 *
 * Topology (k8s):  app pod with FileSink -> emptyDir volume -> sidecar container
 *                  with this binary -> Kafka/NATS/file
 * Topology (host): system service tails /var/log/app
 */
public final class Sidecar implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Sidecar.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SidecarConfig config;
    private final FileTailer tailer;
    private final LogSink targetSink;
    private final AtomicLong forwardedCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();

    public Sidecar(SidecarConfig config) {
        this(config, buildSink(config));
    }

    /** Visible for tests — bring your own sink. */
    public Sidecar(SidecarConfig config, LogSink targetSink) {
        this.config = config;
        this.targetSink = targetSink;
        Checkpoint cp = new Checkpoint(config.checkpointFile());
        this.tailer = new FileTailer(
                config.watchDir(),
                config.fileGlob(),
                cp,
                config.pollIntervalMs(),
                this::forward);
    }

    private static LogSink buildSink(SidecarConfig cfg) {
        switch (cfg.target()) {
            case KAFKA:
                return new KafkaSink(cfg.targetTopic(), cfg.kafkaBootstrap());
            case FILE:
                if (cfg.targetFileDir() == null) {
                    throw new IllegalStateException("SIDECAR_TARGET=FILE requires SIDECAR_TARGET_FILE_DIR");
                }
                return new FileSink(cfg.targetFileDir().resolve("shipped.ndjson"));
            case NATS: {
                try {
                    Class<?> cls = Class.forName("com.logging.sink.NatsJetStreamSink");
                    Constructor<?> ctor = cls.getConstructor(String.class, String.class);
                    return (LogSink) ctor.newInstance(cfg.natsUrl(), cfg.natsSubjectPrefix());
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException("NATS sink unavailable; add jnats to the classpath", e);
                } catch (NoSuchMethodException | IllegalAccessException | InstantiationException e) {
                    throw new IllegalStateException("Failed to construct NATS sink", e);
                } catch (InvocationTargetException e) {
                    throw new IllegalStateException("Failed to construct NATS sink", e.getCause());
                }
            }
            default:
                throw new IllegalStateException("Unsupported sidecar target: " + cfg.target());
        }
    }

    public void start() {
        tailer.start();
        LOG.info("Sidecar started: watch={} target={} ", config.watchDir(), config.target());
    }

    /** Drive a single poll synchronously — useful for tests. */
    public void pollOnce() throws IOException {
        tailer.pollOnce();
    }

    public long forwardedCount() { return forwardedCount.get(); }
    public long failedCount() { return failedCount.get(); }

    /**
     * Each tailed line should already be a serialized envelope; we parse it
     * back into a LogEnvelope so the downstream sink sees the same shape it
     * would have if the app had published directly.
     */
    private void forward(String fileKey, String line) {
        try {
            JsonNode node = MAPPER.readTree(line);
            String logType = node.path("_log_type").asText("unknown");
            String logClass = node.path("_log_class").asText("unknown");
            String version = node.path("_version").asText("0.0.0");
            JsonNode dataNode = node.path("data");
            // Prefer user_id / session_id from the data block as routing key.
            String key = firstNonBlank(
                    dataNode.path("user_id").asText(null),
                    dataNode.path("session_id").asText(null),
                    fileKey);
            Object data = MAPPER.treeToValue(dataNode, Map.class);
            LogEnvelope envelope = new LogEnvelope(key, logType, logClass, version, data);
            targetSink.publish(envelope, (ok, err) -> {
                if (Boolean.TRUE.equals(ok)) forwardedCount.incrementAndGet();
                else failedCount.incrementAndGet();
            });
        } catch (Throwable t) {
            failedCount.incrementAndGet();
            LOG.error("Sidecar failed to forward line from {}: {}", fileKey, line, t);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank() && !"null".equals(v)) return v;
        }
        return null;
    }

    @Override
    public void close() {
        tailer.close();
        targetSink.flush();
        targetSink.close();
        LOG.info("Sidecar closed: forwarded={} failed={}", forwardedCount.get(), failedCount.get());
    }
}
