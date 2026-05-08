package com.logging.demo;

import com.logging.BaseStructuredLogger;
import com.logging.config.LoggerConfig;
import com.logging.config.SinkType;

import java.time.Instant;
import java.util.Map;

/**
 * Improvement #1: same log API, two destinations at once.
 *
 * Configures the logger to fan out to SLF4J (so the existing log appenders
 * keep working — console, file, ELK, Datadog agent, etc.) AND a structured
 * sink — file in this demo, but flip {@link SinkType#KAFKA} or
 * {@link SinkType#NATS} via a one-line config change.
 *
 * Run: mvn -pl demos -am exec:java -Dexec.mainClass=com.logging.demo.DualLoggingDemo
 */
public final class DualLoggingDemo {

    /** Tiny ad-hoc logger so the demo doesn't depend on regenerated code. */
    static final class CheckoutLogger extends BaseStructuredLogger {
        CheckoutLogger(LoggerConfig cfg) {
            super("checkout-events", "Checkout", "checkout_events", "1.0.0", cfg);
        }
        void purchase(String userId, String item, long amountCents) {
            publish(userId, Map.of(
                    "user_id", userId,
                    "item", item,
                    "amount_cents", amountCents,
                    "timestamp", Instant.now().toString()));
        }
    }

    public static void main(String[] args) throws Exception {
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("dual-demo-");
        System.out.println("Writing structured NDJSON to: " + tmp);

        LoggerConfig config = LoggerConfig.builder()
                .sinks(SinkType.SLF4J, SinkType.FILE)
                .fileDir(tmp)
                .slf4jLogger("application")
                .build();

        try (CheckoutLogger logger = new CheckoutLogger(config)) {
            logger.purchase("u_1", "espresso machine", 39900);
            logger.purchase("u_2", "headphones", 24900);
            logger.purchase("u_3", "monitor", 49900);
            logger.flush();
        }

        java.nio.file.Path file = tmp.resolve("checkout.ndjson");
        System.out.println("\n--- Structured file output (" + file + ") ---");
        java.nio.file.Files.readAllLines(file).forEach(System.out::println);
        System.out.println("\n(SLF4J output should appear above this — same records, two paths.)");
    }
}
