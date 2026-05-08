package com.logging.stream.demos;

import com.logging.BaseStructuredLogger;
import com.logging.config.LoggerConfig;
import com.logging.config.SinkType;
import com.logging.stream.sources.FilesystemJsonSource;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Dual-purpose log stream: same NDJSON file feeds both the warehouse path
 * AND a Flink Table API streaming aggregation.
 *
 * Architecture this demonstrates:
 *
 *   App ── FileSink ──> /tmp/sl-flink-demo/warehouse/userevents.ndjson
 *                          │
 *                          ├── tail──> sidecar ──> Iceberg/S3   (warehouse path)
 *                          └── read──> Flink Table API SQL      (streaming path)
 *
 * The single durable NDJSON file is the source of truth for both consumers.
 * The sidecar (already covered by FileSinkSidecarDemo) ships records to the
 * warehouse for ad-hoc queries; Flink reads the same file for sub-second
 * aggregation. Same envelope shape, two independent consumers — exactly the
 * shape you'd get with a Kafka topic and two consumer groups.
 *
 * Run: java -cp "target/classes:target/lib/*" \
 *        com.logging.stream.demos.WarehouseAndStreamingDemo
 */
public final class WarehouseAndStreamingDemo {

    static final class UserEventsLogger extends BaseStructuredLogger {
        UserEventsLogger(LoggerConfig cfg) {
            super("user-events", "UserEvents", "user_events", "1.0.0", cfg);
        }
        void event(String userId, String eventType, long durationMs) {
            publish(userId, Map.of(
                    "user_id", userId,
                    "event_type", eventType,
                    "duration_ms", durationMs,
                    "timestamp", Instant.now().toString()));
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getenv().getOrDefault("DEMO_ROOT", "/tmp/sl-flink-demo/warehouse"));
        Files.createDirectories(root);
        // Clean previous runs so the bounded read sees only this run's data.
        try (var stream = Files.list(root)) {
            stream.forEach(p -> p.toFile().delete());
        }

        System.out.println("==== Warehouse + Streaming demo ====");
        System.out.println("Source NDJSON dir: " + root);
        System.out.println();
        System.out.println("Step 1: app produces user_events via FileSink (warehouse landing zone).");

        LoggerConfig cfg = LoggerConfig.builder()
                .sinks(SinkType.FILE)
                .fileDir(root)
                .build();
        try (UserEventsLogger logger = new UserEventsLogger(cfg)) {
            String[] types = {"click", "click", "click", "purchase", "scroll", "scroll"};
            for (int i = 0; i < 200; i++) {
                String type = types[ThreadLocalRandom.current().nextInt(types.length)];
                long dur = ThreadLocalRandom.current().nextLong(50, 1500);
                logger.event("u_" + (i % 25), type, dur);
            }
            logger.flush();
        }
        System.out.println("  -> wrote " + Files.size(root.resolve("userevents.ndjson")) + " bytes to userevents.ndjson");
        System.out.println();
        System.out.println("Step 2: Flink Table API streaming SQL reads the same file and aggregates.");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        StreamTableEnvironment tEnv = StreamTableEnvironment.create(
                env, EnvironmentSettings.newInstance().inStreamingMode().build());

        FilesystemJsonSource source = new FilesystemJsonSource(
                "user_events_log",
                root.toAbsolutePath().toString(),
                "ROW<`user_id` STRING, `event_type` STRING, `duration_ms` BIGINT, `timestamp` STRING>",
                false /* bounded read for the demo */);

        tEnv.executeSql(source.ddl());

        String sql =
                "SELECT data.event_type AS event_type, " +
                "       COUNT(*) AS event_count, " +
                "       CAST(AVG(CAST(data.duration_ms AS DOUBLE)) AS DECIMAL(10,2)) AS avg_duration_ms " +
                "FROM user_events_log " +
                "WHERE _log_type = 'user_events' " +
                "GROUP BY data.event_type";
        System.out.println("  SQL:");
        System.out.println("    " + sql.replace(" FROM ", "\n    FROM ")
                                       .replace(" WHERE ", "\n    WHERE ")
                                       .replace(" GROUP BY ", "\n    GROUP BY "));
        TableResult result = tEnv.executeSql(sql);

        // Streaming GROUP BY emits an update stream (+I, -U, +U); collect the
        // FINAL state for each key by keeping the last +-something we saw.
        java.util.Map<String, Row> finalState = new java.util.LinkedHashMap<>();
        try (CloseableIterator<Row> it = result.collect()) {
            while (it.hasNext()) {
                Row r = it.next();
                String kind = r.getKind().shortString();
                if (kind.equals("-D") || kind.equals("-U")) {
                    finalState.remove(String.valueOf(r.getField(0)));
                } else {
                    finalState.put(String.valueOf(r.getField(0)), r);
                }
            }
        } catch (Exception ignored) {
            // Mini-cluster shutdown noise after bounded source is exhausted.
        }
        System.out.println();
        System.out.printf("%-15s %-12s %-15s%n", "event_type", "event_count", "avg_duration_ms");
        System.out.println("-----------------------------------------------");
        finalState.values().forEach(r ->
                System.out.printf("%-15s %-12s %-15s%n", r.getField(0), r.getField(1), r.getField(2)));
        System.out.println();
        System.out.println("The source file (" + root.resolve("userevents.ndjson") + ") is still on disk —");
        System.out.println("it would be picked up by the sidecar and shipped to the warehouse,");
        System.out.println("OR queried ad-hoc via Iceberg/Trino. The Flink job consumed it independently.");
    }
}
