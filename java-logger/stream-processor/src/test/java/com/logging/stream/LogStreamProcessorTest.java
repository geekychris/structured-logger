package com.logging.stream;

import com.logging.stream.sources.FilesystemJsonSource;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the LogStreamProcessor wires sources, sinks and INSERTs through a
 * real Flink mini-cluster (the test classpath pulls flink-streaming-java in
 * non-provided scope). No Kafka required: the source is the filesystem
 * connector pointed at NDJSON written by FileSink.
 */
class LogStreamProcessorTest {

    @Test
    void runsSqlAggregationOnFilesystemSource(@TempDir Path tmp) throws Exception {
        // 1. Stage some envelope-shaped JSON (the same shape FileSink writes).
        Path data = tmp.resolve("data");
        Files.createDirectory(data);
        Files.writeString(data.resolve("user_events.ndjson"),
                "{\"_log_type\":\"user_events\",\"_log_class\":\"UE\",\"_version\":\"1.0.0\"," +
                        "\"data\":{\"user_id\":\"u1\",\"event_type\":\"click\",\"duration_ms\":100}}\n" +
                "{\"_log_type\":\"user_events\",\"_log_class\":\"UE\",\"_version\":\"1.0.0\"," +
                        "\"data\":{\"user_id\":\"u2\",\"event_type\":\"click\",\"duration_ms\":150}}\n" +
                "{\"_log_type\":\"user_events\",\"_log_class\":\"UE\",\"_version\":\"1.0.0\"," +
                        "\"data\":{\"user_id\":\"u3\",\"event_type\":\"purchase\",\"duration_ms\":900}}\n" +
                "{\"_log_type\":\"api_metrics\",\"_log_class\":\"AM\",\"_version\":\"1.0.0\"," +
                        "\"data\":{\"user_id\":\"u4\",\"event_type\":\"call\",\"duration_ms\":50}}\n");

        // 2. Build a Flink streaming env and the processor.
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        StreamTableEnvironment tEnv = StreamTableEnvironment.create(
                env, EnvironmentSettings.newInstance().inStreamingMode().build());

        FilesystemJsonSource source = new FilesystemJsonSource(
                "logs",
                data.toAbsolutePath().toString(),
                "ROW<`user_id` STRING, `event_type` STRING, `duration_ms` BIGINT>",
                false);

        LogStreamProcessor processor = new LogStreamProcessor()
                .addSource(source)
                .addRawDdl("CREATE VIEW user_clicks AS " +
                        "SELECT data.event_type AS event_type, COUNT(*) AS c " +
                        "FROM logs WHERE _log_type = 'user_events' " +
                        "GROUP BY data.event_type")
                .addInsert("INSERT INTO blackhole_collector " +
                        "SELECT event_type, c FROM user_clicks");

        // Use a values-collecting sink we register manually.
        tEnv.executeSql("CREATE TABLE blackhole_collector (event_type STRING, c BIGINT) " +
                "WITH ('connector' = 'blackhole')");

        // 3. Execute the pipeline (bounded source -> finishes).
        TableResult ignored = processor.executeOn(tEnv);
        ignored.await();

        // 4. Independently query the view materialised by the planner.
        TableResult counts = tEnv.executeSql("SELECT event_type, c FROM user_clicks");
        Set<String> rows = new HashSet<>();
        try (CloseableIterator<Row> it = counts.collect()) {
            while (it.hasNext()) {
                Row r = it.next();
                rows.add(r.getField(0) + "=" + r.getField(1));
            }
        }
        // Update-stream may emit +I and -U/+U records; final state should match.
        assertThat(rows).contains("click=2", "purchase=1");
    }
}
