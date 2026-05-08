package com.logging.sink;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FileSinkTest {

    @Test
    void writesOneJsonLinePerEnvelope(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("events.ndjson");
        try (FileSink sink = new FileSink(file)) {
            sink.publish(envelope("u1", "click"), null);
            sink.publish(envelope("u2", "purchase"), null);
            sink.flush();
        }
        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(2);

        ObjectMapper m = new ObjectMapper();
        assertThat(m.readTree(lines.get(0)).get("_log_type").asText()).isEqualTo("user_events");
        assertThat(m.readTree(lines.get(0)).get("data").get("user_id").asText()).isEqualTo("u1");
        assertThat(m.readTree(lines.get(1)).get("data").get("event_type").asText()).isEqualTo("purchase");
    }

    @Test
    void rotatesOnceSizeThresholdExceeded(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("rotated.ndjson");
        // Tiny rotation threshold so a few records trip it.
        try (FileSink sink = new FileSink(file, 200, false)) {
            for (int i = 0; i < 10; i++) {
                sink.publish(envelope("u" + i, "click"), null);
            }
        }
        // Active file plus at least one rotated `.N` file.
        try (Stream<Path> entries = Files.list(file.getParent())) {
            long count = entries.filter(p -> p.getFileName().toString().startsWith("rotated.ndjson")).count();
            assertThat(count).isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    void publishAfterCloseReportsFailure(@TempDir Path tmp) {
        FileSink sink = new FileSink(tmp.resolve("e.ndjson"));
        sink.close();
        boolean[] ok = {true};
        sink.publish(envelope("u", "x"), (success, err) -> ok[0] = success);
        assertThat(ok[0]).isFalse();
    }

    private static LogEnvelope envelope(String userId, String eventType) {
        return new LogEnvelope(userId, "user_events", "UserEvents", "1.0.0",
                Map.of("user_id", userId, "event_type", eventType));
    }
}
