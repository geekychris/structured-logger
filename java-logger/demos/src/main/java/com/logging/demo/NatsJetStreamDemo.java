package com.logging.demo;

import com.logging.BaseStructuredLogger;
import com.logging.config.LoggerConfig;
import com.logging.config.SinkType;

import java.time.Instant;
import java.util.Map;

/**
 * Improvement #2 (broker path): publish straight to NATS JetStream — a
 * lighter-weight alternative to Kafka for many workloads.
 *
 * Prereq: a local NATS server with JetStream enabled.
 *   docker run -p 4222:4222 nats:2.10 -js
 *
 * Run: NATS_URL=nats://127.0.0.1:4222 mvn -pl demos -am exec:java \
 *        -Dexec.mainClass=com.logging.demo.NatsJetStreamDemo
 *
 * Cost/time tradeoffs vs Kafka:
 *   * Lower hot-path latency, smaller broker footprint.
 *   * No ZooKeeper/Kraft; one binary.
 *   * Built-in subject hierarchy maps cleanly to {@code logs.<log_type>} routing.
 *   * Less mature ecosystem of warehousing connectors than Kafka.
 */
public final class NatsJetStreamDemo {

    static final class TelemetryLogger extends BaseStructuredLogger {
        TelemetryLogger(LoggerConfig cfg) {
            super("telemetry", "Telemetry", "telemetry", "1.0.0", cfg);
        }
        void cpu(String host, double pct) {
            publish(host, Map.of(
                    "host", host, "metric", "cpu", "value", pct,
                    "timestamp", Instant.now().toString()));
        }
    }

    public static void main(String[] args) throws Exception {
        String natsUrl = System.getenv().getOrDefault("NATS_URL", "nats://127.0.0.1:4222");
        LoggerConfig config = LoggerConfig.builder()
                .sinks(SinkType.NATS)
                .natsUrl(natsUrl)
                .natsSubjectPrefix("logs")
                .build();

        System.out.println("Publishing to NATS JetStream at " + natsUrl + " (subjects: logs.telemetry.*)");
        try (TelemetryLogger logger = new TelemetryLogger(config)) {
            for (int i = 0; i < 5; i++) {
                logger.cpu("host-" + i, 42.0 + i);
            }
            logger.flush();
            System.out.println("Done. Verify with: nats sub 'logs.>' (in another terminal).");
        } catch (Throwable t) {
            System.err.println("NATS demo failed: " + t.getMessage());
            System.err.println("Hint: start a local broker with `docker run -p 4222:4222 nats:2.10 -js`");
            throw t;
        }
    }
}
