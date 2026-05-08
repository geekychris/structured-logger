package com.logging.demo;

import com.logging.BaseStructuredLogger;
import com.logging.config.LoggerConfig;
import com.logging.config.SinkType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/**
 * Improvement #3: stream processing.
 *
 * The streaming module (structured-logging-stream-processor) lives in its own
 * Maven module so the core library doesn't drag Flink onto every classpath.
 * This demo prepares input that the Flink job can consume:
 *
 *   1. Run this main first — it produces NDJSON in $TMP/flink-demo-in/
 *   2. Run the Flink job:
 *        mvn -pl stream-processor -am exec:java \
 *          -Dexec.mainClass=com.logging.stream.jobs.UserEventsAggregationJob \
 *          -Dexec.args="--source filesystem --path $TMP/flink-demo-in"
 *      OR (preferred for production) point UserEventsAggregationJob at a
 *      Kafka cluster fed by the sidecar.
 *
 * The Flink job reads {@code _log_type=user_events} records, tumbles them
 * into 1-minute windows by event_type, and prints counts. The same SQL
 * applies whether the source is filesystem (replay), Kafka, or — once the
 * sidecar bridges it — NATS JetStream.
 */
public final class FlinkStreamingDemo {

    static final class UELogger extends BaseStructuredLogger {
        UELogger(LoggerConfig cfg) {
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
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "flink-demo-in");
        Files.createDirectories(dir);

        LoggerConfig cfg = LoggerConfig.builder()
                .sinks(SinkType.FILE)
                .fileDir(dir)
                .build();
        try (UELogger logger = new UELogger(cfg)) {
            String[] types = {"click", "click", "click", "purchase", "scroll"};
            for (int i = 0; i < 50; i++) {
                logger.event("u_" + (i % 10), types[i % types.length], 50 + i * 3);
            }
            logger.flush();
        }

        System.out.println("Wrote sample user_events NDJSON to: " + dir);
        System.out.println("Now run the Flink job — see class javadoc.");
    }
}
