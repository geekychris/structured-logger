package com.logging.demo;

import com.logging.config.LoggerConfig;
import com.logging.config.SinkType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the dual-logging demo's logger setup actually fans out — the file
 * sink should receive the records even if SLF4J is the loud one.
 */
class DualLoggingDemoTest {

    @Test
    void logsToBothFileAndSlf4j(@TempDir Path tmp) throws Exception {
        LoggerConfig cfg = LoggerConfig.builder()
                .sinks(SinkType.SLF4J, SinkType.FILE)
                .fileDir(tmp)
                .build();
        try (DualLoggingDemo.CheckoutLogger logger = new DualLoggingDemo.CheckoutLogger(cfg)) {
            logger.purchase("u1", "thing", 1000);
            logger.flush();
        }
        Path file = tmp.resolve("checkout.ndjson");
        assertThat(file).exists();
        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("\"_log_type\":\"checkout_events\"");
        assertThat(lines.get(0)).contains("\"user_id\":\"u1\"");
    }
}
