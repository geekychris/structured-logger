package com.logging.sidecar;

import com.logging.config.LoggerConfig;
import com.logging.config.SinkType;
import com.logging.sink.LogEnvelope;
import com.logging.sink.LogSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end pipeline test with no external services:
 *
 *   App writes via FileSink (composed inside core's BaseStructuredLogger)
 *     -> NDJSON file in shared dir
 *     -> Sidecar tails the file
 *     -> Re-publishes through a CapturingSink
 *
 * This is the same shape that runs in production with Kafka or NATS as the
 * downstream — only the sink type differs.
 */
class SidecarPipelineTest {

    /** Concrete logger used solely for this test (mirrors what the code generator produces). */
    static final class CartLogger extends com.logging.BaseStructuredLogger {
        CartLogger(LoggerConfig cfg) {
            super("cart-events", "Cart", "cart_events", "1.0.0", cfg);
        }
        void purchase(String userId, String item) {
            publish(userId, java.util.Map.of("user_id", userId, "item", item));
        }
    }

    /** Captures forwarded envelopes for assertions. */
    static final class CapturingSink implements LogSink {
        final List<LogEnvelope> captured = new ArrayList<>();
        @Override public String name() { return "capture"; }
        @Override public void publish(LogEnvelope e, BiConsumer<Boolean, Throwable> cb) {
            captured.add(e);
            if (cb != null) cb.accept(true, null);
        }
        @Override public void flush() {}
        @Override public void close() {}
    }

    @Test
    void appWritesFileSidecarShipsIt(@TempDir Path tmp) throws Exception {
        Path watch = tmp.resolve("logs");
        java.nio.file.Files.createDirectory(watch);

        // App side: FileSink only.
        LoggerConfig cfg = LoggerConfig.builder()
                .sinks(SinkType.FILE)
                .fileDir(watch)
                .build();
        try (CartLogger logger = new CartLogger(cfg)) {
            logger.purchase("u1", "book");
            logger.purchase("u2", "lamp");
            logger.flush();
        }

        // Sidecar side: tails the same dir, forwards into our capture sink.
        SidecarConfig scfg = SidecarConfig.builder()
                .watchDir(watch)
                .checkpointFile(tmp.resolve("cp.json"))
                .pollIntervalMs(100)
                .target(SinkType.FILE)        // arbitrary, overridden by test sink
                .targetFileDir(tmp)
                .build();
        CapturingSink capture = new CapturingSink();
        try (Sidecar sidecar = new Sidecar(scfg, capture)) {
            sidecar.pollOnce();
        }

        assertThat(capture.captured).hasSize(2);
        LogEnvelope first = capture.captured.get(0);
        assertThat(first.getLogType()).isEqualTo("cart_events");
        assertThat(first.getLogClass()).isEqualTo("Cart");
        assertThat(first.getVersion()).isEqualTo("1.0.0");
        assertThat(first.getKey()).isEqualTo("u1"); // re-extracted from data.user_id
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> data = (java.util.Map<String, Object>) first.getData();
        assertThat(data).containsEntry("user_id", "u1").containsEntry("item", "book");
    }

    @Test
    void countsForwardedAndFailed(@TempDir Path tmp) throws Exception {
        Path watch = tmp.resolve("logs");
        java.nio.file.Files.createDirectory(watch);
        java.nio.file.Files.writeString(
                watch.resolve("events.ndjson"),
                "{\"_log_type\":\"t\",\"_log_class\":\"C\",\"_version\":\"1\",\"data\":{\"user_id\":\"u\"}}\n" +
                "this is not valid json\n");

        SidecarConfig scfg = SidecarConfig.builder()
                .watchDir(watch)
                .checkpointFile(tmp.resolve("cp.json"))
                .target(SinkType.FILE)
                .targetFileDir(tmp)
                .build();
        CapturingSink capture = new CapturingSink();
        try (Sidecar sidecar = new Sidecar(scfg, capture)) {
            sidecar.pollOnce();
            assertThat(sidecar.forwardedCount()).isEqualTo(1);
            assertThat(sidecar.failedCount()).isEqualTo(1);
        }
    }
}
