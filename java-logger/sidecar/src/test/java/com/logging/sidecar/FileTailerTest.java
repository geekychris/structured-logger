package com.logging.sidecar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileTailerTest {

    @Test
    void emitsLinesAppendedSinceLastPoll(@TempDir Path tmp) throws Exception {
        Path watch = Files.createDirectory(tmp.resolve("watch"));
        Path file = watch.resolve("events.ndjson");
        Files.writeString(file, "first\nsecond\n");

        Checkpoint cp = new Checkpoint(tmp.resolve("cp.json"));
        List<String> lines = new ArrayList<>();
        try (FileTailer tailer = new FileTailer(watch, "*.ndjson", cp, 100, (k, l) -> lines.add(l))) {
            tailer.pollOnce();
            assertThat(lines).containsExactly("first", "second");

            // Append more — only the new lines should be emitted.
            Files.writeString(file, "third\n", StandardOpenOption.APPEND);
            tailer.pollOnce();
            assertThat(lines).containsExactly("first", "second", "third");
        }
    }

    @Test
    void survivesRotationOfActiveFile(@TempDir Path tmp) throws Exception {
        Path watch = Files.createDirectory(tmp.resolve("watch"));
        Path file = watch.resolve("events.ndjson");
        Files.writeString(file, "a\nb\n");

        Checkpoint cp = new Checkpoint(tmp.resolve("cp.json"));
        List<String> lines = new ArrayList<>();
        try (FileTailer tailer = new FileTailer(watch, "*.ndjson", cp, 100, (k, l) -> lines.add(l))) {
            tailer.pollOnce();

            // Truncate (rotation: previous file moved out of the way, fresh start).
            Files.write(file, new byte[0]);
            Files.writeString(file, "c\n");
            tailer.pollOnce();
            assertThat(lines).containsExactly("a", "b", "c");
        }
    }

    @Test
    void resumesFromCheckpointAcrossRestart(@TempDir Path tmp) throws Exception {
        Path watch = Files.createDirectory(tmp.resolve("watch"));
        Path file = watch.resolve("events.ndjson");
        Files.writeString(file, "alpha\nbeta\n");

        Checkpoint cp = new Checkpoint(tmp.resolve("cp.json"));
        List<String> linesA = new ArrayList<>();
        try (FileTailer tailer = new FileTailer(watch, "*.ndjson", cp, 100, (k, l) -> linesA.add(l))) {
            tailer.pollOnce();
        }
        assertThat(linesA).containsExactly("alpha", "beta");

        // Append between "restarts".
        Files.writeString(file, "gamma\n", StandardOpenOption.APPEND);

        Checkpoint cp2 = new Checkpoint(tmp.resolve("cp.json"));
        List<String> linesB = new ArrayList<>();
        try (FileTailer tailer = new FileTailer(watch, "*.ndjson", cp2, 100, (k, l) -> linesB.add(l))) {
            tailer.pollOnce();
        }
        assertThat(linesB).containsExactly("gamma");
    }

    @Test
    void multipleFilesAreTrackedIndependently(@TempDir Path tmp) throws Exception {
        Path watch = Files.createDirectory(tmp.resolve("watch"));
        Files.writeString(watch.resolve("user_events.ndjson"), "u1\nu2\n");
        Files.writeString(watch.resolve("api_metrics.ndjson"), "m1\n");

        Checkpoint cp = new Checkpoint(tmp.resolve("cp.json"));
        List<String> events = new ArrayList<>();
        try (FileTailer tailer = new FileTailer(watch, "*.ndjson", cp, 100,
                (k, l) -> events.add(k + ":" + l))) {
            tailer.pollOnce();
        }
        assertThat(events).containsExactlyInAnyOrder(
                "user_events.ndjson:u1",
                "user_events.ndjson:u2",
                "api_metrics.ndjson:m1");
    }
}
