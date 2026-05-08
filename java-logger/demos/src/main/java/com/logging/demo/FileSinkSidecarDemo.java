package com.logging.demo;

import com.logging.BaseStructuredLogger;
import com.logging.config.LoggerConfig;
import com.logging.config.SinkType;
import com.logging.sidecar.Sidecar;
import com.logging.sidecar.SidecarConfig;
import com.logging.sink.FileSink;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/**
 * Improvement #2 (file path): the cheapest hot path. The application writes
 * NDJSON locally; a separate sidecar process tails the file and ships it
 * elsewhere. In this demo the "elsewhere" is just another file so the demo
 * runs without Kafka/NATS — but in production this is exactly the pattern
 * used to ship to S3, Kafka, NATS or HTTP.
 *
 * Cost/time tradeoffs (vs. logging directly to Kafka from the app):
 *   * Hot-path latency: file write ~0.01 ms vs Kafka send ~1-5 ms.
 *   * App resilience: broker outage backs up to local disk, not memory.
 *   * Operational: one more process (kubelet sidecar in k8s, systemd unit on host).
 */
public final class FileSinkSidecarDemo {

    static final class OrderLogger extends BaseStructuredLogger {
        OrderLogger(LoggerConfig cfg) {
            super("orders", "Orders", "orders", "1.0.0", cfg);
        }
        void place(String userId, String sku, int qty) {
            publish(userId, Map.of(
                    "user_id", userId,
                    "sku", sku,
                    "qty", qty,
                    "timestamp", Instant.now().toString()));
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("file-sidecar-demo-");
        Path appDir = Files.createDirectory(root.resolve("app-logs"));
        Path shippedDir = Files.createDirectory(root.resolve("shipped"));
        System.out.println("App writes to:     " + appDir);
        System.out.println("Sidecar ships to:  " + shippedDir);

        // 1) The app: FileSink only.
        LoggerConfig appConfig = LoggerConfig.builder()
                .sinks(SinkType.FILE)
                .fileDir(appDir)
                .build();
        try (OrderLogger logger = new OrderLogger(appConfig)) {
            for (int i = 0; i < 5; i++) {
                logger.place("u_" + i, "sku-" + (100 + i), i + 1);
            }
            logger.flush();
        }
        System.out.println("Application produced: " +
                Files.list(appDir).map(Path::getFileName).map(Object::toString).toList());

        // 2) The sidecar: file in -> file out (production: file in -> Kafka/NATS).
        SidecarConfig sCfg = SidecarConfig.builder()
                .watchDir(appDir)
                .checkpointFile(root.resolve("positions.json"))
                .pollIntervalMs(100)
                .target(SinkType.FILE)
                .targetFileDir(shippedDir)
                .build();
        try (Sidecar sidecar = new Sidecar(sCfg, new FileSink(shippedDir.resolve("delivered.ndjson")))) {
            sidecar.pollOnce();
            System.out.println("Sidecar forwarded: " + sidecar.forwardedCount() +
                    " (failed: " + sidecar.failedCount() + ")");
        }
        System.out.println("\n--- Delivered file ---");
        Files.readAllLines(shippedDir.resolve("delivered.ndjson")).forEach(System.out::println);
    }
}
