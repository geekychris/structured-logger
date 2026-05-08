package com.logging.sidecar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CheckpointTest {

    @Test
    void roundTripsOffsetsThroughDisk(@TempDir Path tmp) {
        Path file = tmp.resolve("positions.json");
        Checkpoint a = new Checkpoint(file);
        a.update("user_events.ndjson", 1024);
        a.update("api_metrics.ndjson", 2048);

        Checkpoint b = new Checkpoint(file);
        assertThat(b.offsetFor("user_events.ndjson")).isEqualTo(1024);
        assertThat(b.offsetFor("api_metrics.ndjson")).isEqualTo(2048);
        assertThat(b.offsetFor("missing.ndjson")).isZero();
    }

    @Test
    void treatsAbsentFileAsEmpty(@TempDir Path tmp) {
        Checkpoint cp = new Checkpoint(tmp.resolve("none.json"));
        assertThat(cp.snapshot()).isEmpty();
    }
}
