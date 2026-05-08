package com.logging.demo;

import com.logging.config.LoggerConfig;
import com.logging.config.SinkType;
import com.logging.sidecar.Sidecar;
import com.logging.sidecar.SidecarConfig;
import com.logging.sink.FileSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Black-box test of the file -> sidecar -> file pipeline used by the demo.
 * The same shape applies to file -> sidecar -> Kafka/NATS in production.
 */
class FileSinkSidecarDemoTest {

    @Test
    void appLogsAreShippedByTheSidecar(@TempDir Path tmp) throws Exception {
        Path appDir = Files.createDirectory(tmp.resolve("app"));
        Path shippedDir = Files.createDirectory(tmp.resolve("shipped"));

        LoggerConfig cfg = LoggerConfig.builder()
                .sinks(SinkType.FILE)
                .fileDir(appDir)
                .build();
        try (FileSinkSidecarDemo.OrderLogger logger = new FileSinkSidecarDemo.OrderLogger(cfg)) {
            for (int i = 0; i < 3; i++) logger.place("u" + i, "sku", i + 1);
            logger.flush();
        }

        SidecarConfig sCfg = SidecarConfig.builder()
                .watchDir(appDir)
                .checkpointFile(tmp.resolve("cp.json"))
                .target(SinkType.FILE)
                .targetFileDir(shippedDir)
                .build();
        try (Sidecar sidecar = new Sidecar(sCfg, new FileSink(shippedDir.resolve("delivered.ndjson")))) {
            sidecar.pollOnce();
            assertThat(sidecar.forwardedCount()).isEqualTo(3);
            assertThat(sidecar.failedCount()).isZero();
        }
        List<String> lines = Files.readAllLines(shippedDir.resolve("delivered.ndjson"));
        assertThat(lines).hasSize(3);
        for (String line : lines) {
            assertThat(line).contains("\"_log_type\":\"orders\"");
        }
    }
}
