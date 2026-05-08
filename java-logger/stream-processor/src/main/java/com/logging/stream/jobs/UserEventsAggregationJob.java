package com.logging.stream.jobs;

import com.logging.stream.LogStreamProcessor;
import com.logging.stream.sinks.PrintSink;
import com.logging.stream.sources.KafkaJsonSource;
import org.apache.flink.table.api.TableResult;

/**
 * Reference Flink Table API job: rolls user_events into 1-minute tumbling
 * windows by event_type and prints the result.
 *
 * Demonstrates the intended ergonomic: ops bring SQL, the processor wires
 * source + sink. Swap out {@link KafkaJsonSource} for a NATS-backed Kafka
 * (via the sidecar) or for {@link com.logging.stream.sources.FilesystemJsonSource}
 * to replay history without any broker.
 */
public final class UserEventsAggregationJob {

    public static TableResult run(String bootstrapServers, String topic) {
        KafkaJsonSource source = KafkaJsonSource.builder()
                .tableName("logs")
                .topic(topic)
                .bootstrapServers(bootstrapServers)
                .groupId("user-events-agg")
                .startingOffsets("earliest-offset")
                .dataRowSchema(
                        "ROW<`user_id` STRING, `session_id` STRING, `event_type` STRING, " +
                        "`page_url` STRING, `device_type` STRING, `duration_ms` BIGINT, " +
                        "`timestamp` STRING>")
                .build();

        PrintSink sink = new PrintSink("event_counts",
                "(window_start TIMESTAMP_LTZ(3), window_end TIMESTAMP_LTZ(3), event_type STRING, total BIGINT)");

        String insert = "INSERT INTO event_counts " +
                "SELECT window_start, window_end, data.event_type AS event_type, COUNT(*) AS total " +
                "FROM TABLE(TUMBLE(TABLE logs, DESCRIPTOR(event_time), INTERVAL '1' MINUTE)) " +
                "WHERE _log_type = 'user_events' " +
                "GROUP BY window_start, window_end, data.event_type";

        return new LogStreamProcessor()
                .addSource(source)
                .addSink(sink)
                .addInsert(insert)
                .execute();
    }

    public static void main(String[] args) throws Exception {
        String bootstrap = System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        String topic = System.getenv().getOrDefault("LOGS_TOPIC", "logs.shared");
        TableResult result = run(bootstrap, topic);
        result.await();
    }
}
