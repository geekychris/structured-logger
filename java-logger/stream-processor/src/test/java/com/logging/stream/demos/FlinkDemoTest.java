package com.logging.stream.demos;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for the two streaming demos. They run the entire pipeline:
 * FileSink writes NDJSON, Flink Table API reads it, SQL aggregates.
 *
 * Each demo's main() does its own cleanup; we just verify it terminates
 * without throwing and produces the expected source file.
 */
class FlinkDemoTest {

    @Test
    void warehouseAndStreamingDemoRunsEndToEnd(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        System.setProperty("DEMO_ROOT_OVERRIDE", "ignored"); // marker; actual env via process env not available
        // Direct env var override through reflection isn't worth it; instead run main() and let it use /tmp.
        // Test passes if main returns without throwing; output assertions are noisy with Flink logs.
        Path root = Path.of("/tmp/sl-flink-demo/warehouse-test");
        Files.createDirectories(root);
        try (var s = Files.list(root)) { s.forEach(p -> p.toFile().delete()); }
        String prevRoot = System.getenv("DEMO_ROOT");
        // We can't override env vars in-process portably; use the default path the demo writes to.
        WarehouseAndStreamingDemo.main(new String[]{});
        Path source = Path.of("/tmp/sl-flink-demo/warehouse/userevents.ndjson");
        assertThat(source).exists();
        assertThat(Files.size(source)).isGreaterThan(1000);
    }

    @Test
    void streamOnlyDemoRunsEndToEnd() throws Exception {
        StreamOnlyDemo.main(new String[]{});
        Path source = Path.of("/tmp/sl-flink-demo/stream-only/sessionpings.ndjson");
        assertThat(source).exists();
        assertThat(Files.size(source)).isGreaterThan(1000);
    }
}
