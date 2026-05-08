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
import java.util.concurrent.ThreadLocalRandom;

/**
 * Long-running version of FileSinkSidecarDemo: produces one record every
 * second for up to {@code DEMO_SECONDS} (default 120) while the sidecar
 * runs live in the background. Open two terminals and tail the input and
 * output files to watch records flow through the pipeline in real time.
 */
public final class LiveSidecarDemo {

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
        Path root = Path.of(System.getenv().getOrDefault("DEMO_ROOT", "/tmp/sl-demo"));
        Path appDir = root.resolve("app-logs");
        Path shippedDir = root.resolve("shipped");
        Files.createDirectories(appDir);
        Files.createDirectories(shippedDir);
        Path delivered = shippedDir.resolve("delivered.ndjson");
        // Start clean each run so tail -f shows only this run's data.
        Files.deleteIfExists(delivered);
        Files.deleteIfExists(appDir.resolve("orders.ndjson"));
        Files.deleteIfExists(root.resolve("positions.json"));

        int seconds = Integer.parseInt(System.getenv().getOrDefault("DEMO_SECONDS", "120"));

        System.out.println("================ live sidecar demo ================");
        System.out.println("App writes to:    " + appDir.resolve("orders.ndjson"));
        System.out.println("Sidecar writes:   " + delivered);
        System.out.println();
        System.out.println("In another terminal:");
        System.out.println("  tail -f " + delivered);
        System.out.println("  # or watch the source:");
        System.out.println("  tail -f " + appDir.resolve("orders.ndjson"));
        System.out.println();
        System.out.println("Producing for " + seconds + "s ...");
        System.out.println("====================================================");

        LoggerConfig appConfig = LoggerConfig.builder()
                .sinks(SinkType.FILE)
                .fileDir(appDir)
                .build();

        SidecarConfig sCfg = SidecarConfig.builder()
                .watchDir(appDir)
                .checkpointFile(root.resolve("positions.json"))
                .pollIntervalMs(500)
                .target(SinkType.FILE)
                .targetFileDir(shippedDir)
                .build();

        FileSink shippedSink = new FileSink(delivered);
        try (Sidecar sidecar = new Sidecar(sCfg, shippedSink);
             OrderLogger logger = new OrderLogger(appConfig)) {
            sidecar.start();

            String[] skus = {"sku-100", "sku-200", "sku-300", "sku-400"};
            long deadline = System.currentTimeMillis() + seconds * 1000L;
            int seq = 0;
            while (System.currentTimeMillis() < deadline) {
                String user = "u_" + ThreadLocalRandom.current().nextInt(1000, 9999);
                String sku = skus[seq % skus.length];
                int qty = ThreadLocalRandom.current().nextInt(1, 5);
                logger.place(user, sku, qty);
                logger.flush();          // push app NDJSON to disk
                Thread.sleep(500);       // let the sidecar's tailer pick it up
                shippedSink.flush();     // make the shipped file readable to a tail -f
                seq++;
                Thread.sleep(500);
            }
            Thread.sleep(1000);
            shippedSink.flush();
            System.out.println("\nProduced " + seq + " records; sidecar forwarded " +
                    sidecar.forwardedCount() + " (failed: " + sidecar.failedCount() + ")");
        }
    }
}
