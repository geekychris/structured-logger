package com.logging.stream.demos;

import com.logging.BaseStructuredLogger;
import com.logging.config.LoggerConfig;
import com.logging.config.SinkType;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Streaming-only log stream: high-volume ephemeral events that don't need
 * a warehouse table. Only the rolled-up aggregates leaving the Flink job
 * are kept; the source NDJSON files have a short retention.
 *
 *   App ── FileSink ──> /tmp/sl-flink-demo/stream-only/session_pings.ndjson
 *                          │
 *                          └── read──> Flink Table API SQL (TUMBLE window)
 *                                          │
 *                                          └─> rollup sink (the only thing kept)
 *
 * Why streaming-only? Some events are not interesting individually — only
 * their distribution matters (heartbeats, RPM counters, queue depths, etc).
 * Persisting them at row granularity is pure cost. The Flink Table API can
 * compute windowed rollups in real time; once aggregated, the source files
 * can be deleted on a tight schedule.
 *
 * This demo uses a 5-second tumbling window over synthesized session pings,
 * counting active sessions per region. Timestamps are spread across multiple
 * windows so the SQL produces interesting output.
 *
 * Run: java -cp "target/classes:target/lib/*" \
 *        com.logging.stream.demos.StreamOnlyDemo
 */
public final class StreamOnlyDemo {

    static final class SessionPingLogger extends BaseStructuredLogger {
        SessionPingLogger(LoggerConfig cfg) {
            super("session-pings", "SessionPings", "session_ping", "1.0.0", cfg);
        }
        void ping(String sessionId, String region, Instant timestamp) {
            publish(sessionId, Map.of(
                    "session_id", sessionId,
                    "region", region,
                    "ts_millis", timestamp.toEpochMilli()));
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getenv().getOrDefault("DEMO_ROOT", "/tmp/sl-flink-demo/stream-only"));
        Files.createDirectories(root);
        try (var stream = Files.list(root)) {
            stream.forEach(p -> p.toFile().delete());
        }

        System.out.println("==== Streaming-only demo ====");
        System.out.println("Source NDJSON dir (transient): " + root);
        System.out.println();
        System.out.println("Step 1: produce 600 session pings spanning 30 seconds across 3 regions.");

        LoggerConfig cfg = LoggerConfig.builder()
                .sinks(SinkType.FILE)
                .fileDir(root)
                .build();

        Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(30, ChronoUnit.SECONDS);
        String[] regions = {"us-east", "us-west", "eu-central"};
        try (SessionPingLogger logger = new SessionPingLogger(cfg)) {
            for (int sec = 0; sec < 30; sec++) {
                Instant ts = base.plus(sec, ChronoUnit.SECONDS);
                for (int i = 0; i < 20; i++) {
                    String region = regions[(sec + i) % regions.length];
                    logger.ping("s_" + (sec * 100 + i), region, ts);
                }
            }
            logger.flush();
        }
        long bytes = Files.size(root.resolve("sessionpings.ndjson"));
        System.out.println("  -> wrote " + bytes + " bytes / 600 records");
        System.out.println();
        System.out.println("Step 2: Flink Table API tumbling-window SQL aggregates them.");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        StreamTableEnvironment tEnv = StreamTableEnvironment.create(
                env, EnvironmentSettings.newInstance().inStreamingMode().build());

        // Inline DDL: declare WATERMARK directly on a computed rowtime column
        // so the TUMBLE TVF has proper time semantics. The JSON `timestamp` is
        // a STRING; we convert and watermark in one go.
        String ddl =
                "CREATE TABLE pings (\n" +
                "  `_log_type`  STRING,\n" +
                "  `_log_class` STRING,\n" +
                "  `_version`   STRING,\n" +
                "  `data` ROW<`session_id` STRING, `region` STRING, `ts_millis` BIGINT>,\n" +
                "  `event_time` AS TO_TIMESTAMP_LTZ(`data`.`ts_millis`, 3),\n" +
                "  WATERMARK FOR `event_time` AS `event_time` - INTERVAL '1' SECOND\n" +
                ") WITH (\n" +
                "  'connector' = 'filesystem',\n" +
                "  'path' = '" + root.toAbsolutePath() + "',\n" +
                "  'format' = 'json',\n" +
                "  'json.ignore-parse-errors' = 'true'\n" +
                ")";
        tEnv.executeSql(ddl);

        String sql =
                "SELECT window_start, data.region AS region, COUNT(*) AS active_pings " +
                "FROM TABLE(TUMBLE(TABLE pings, DESCRIPTOR(event_time), INTERVAL '5' SECOND)) " +
                "WHERE _log_type = 'session_ping' " +
                "GROUP BY window_start, window_end, data.region";
        System.out.println("  SQL:");
        System.out.println("    " + sql.replace(" FROM ", "\n    FROM ")
                                       .replace(" WHERE ", "\n    WHERE ")
                                       .replace(" GROUP BY ", "\n    GROUP BY "));
        TableResult result = tEnv.executeSql(sql);

        java.util.List<Row> rows = new java.util.ArrayList<>();
        try (CloseableIterator<Row> it = result.collect()) {
            while (it.hasNext()) {
                Row r = it.next();
                String kind = r.getKind().shortString();
                if (kind.equals("+I") || kind.equals("+U")) rows.add(r);
            }
        } catch (Exception ignored) {
            // Mini-cluster shutdown noise after bounded source is exhausted.
        }
        System.out.println();
        System.out.printf("%-25s %-12s %-15s%n", "window_start", "region", "active_pings");
        System.out.println("-------------------------------------------------------");
        rows.forEach(r ->
                System.out.printf("%-25s %-12s %-15s%n", r.getField(0), r.getField(1), r.getField(2)));
        System.out.println();
        System.out.println("Only the rollups above are typically retained.");
        System.out.println("The source file (" + root.resolve("sessionpings.ndjson") + ")");
        System.out.println("would be deleted on a short retention schedule (e.g., 1 hour).");
    }
}
