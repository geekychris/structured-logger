package com.logging.stream.sources;

import com.logging.stream.StreamSource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Flink Kafka source emitting envelope-shaped JSON. The DDL deliberately
 * mirrors the {@link com.logging.sink.LogEnvelope} layout: top-level routing
 * metadata is exposed and the payload is unpacked from the {@code data} object
 * via Flink's JSON ROW projection so SQL can query e.g. {@code data.user_id}.
 *
 * Designed for the shared-topic pattern where many log_types share one Kafka
 * topic; the WHERE clause in your SELECT filters by {@code _log_type} (see
 * {@link com.logging.stream.jobs.UserEventsAggregationJob}).
 */
public final class KafkaJsonSource implements StreamSource {

    private final String tableName;
    private final String topic;
    private final String bootstrapServers;
    private final String groupId;
    private final String startingOffsets;   // earliest-offset | latest-offset | group-offsets
    private final String dataRowSchema;     // ROW<...> projection for "data" field

    private KafkaJsonSource(Builder b) {
        this.tableName = b.tableName;
        this.topic = b.topic;
        this.bootstrapServers = b.bootstrapServers;
        this.groupId = b.groupId;
        this.startingOffsets = b.startingOffsets;
        this.dataRowSchema = b.dataRowSchema;
    }

    @Override
    public String tableName() {
        return tableName;
    }

    @Override
    public String ddl() {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("connector", "kafka");
        options.put("topic", topic);
        options.put("properties.bootstrap.servers", bootstrapServers);
        options.put("properties.group.id", groupId);
        options.put("scan.startup.mode", startingOffsets);
        options.put("format", "json");
        options.put("json.fail-on-missing-field", "false");
        options.put("json.ignore-parse-errors", "true");

        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(tableName).append(" (\n");
        sb.append("  `_log_type`   STRING,\n");
        sb.append("  `_log_class`  STRING,\n");
        sb.append("  `_version`    STRING,\n");
        sb.append("  `data`        ").append(dataRowSchema).append(",\n");
        sb.append("  `event_time`  AS COALESCE(CAST(`data`.`timestamp` AS TIMESTAMP_LTZ(3)), PROCTIME()),\n");
        sb.append("  WATERMARK FOR `event_time` AS `event_time` - INTERVAL '5' SECOND\n");
        sb.append(") WITH (\n");
        boolean first = true;
        for (Map.Entry<String, String> e : options.entrySet()) {
            if (!first) sb.append(",\n");
            sb.append("  '").append(e.getKey()).append("' = '").append(e.getValue()).append("'");
            first = false;
        }
        sb.append("\n)");
        return sb.toString();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String tableName = "logs";
        private String topic = "logs.shared";
        private String bootstrapServers = "localhost:9092";
        private String groupId = "stream-processor";
        private String startingOffsets = "latest-offset";
        private String dataRowSchema = "ROW<`user_id` STRING, `event_type` STRING, `duration_ms` BIGINT, `timestamp` STRING>";

        public Builder tableName(String v) { this.tableName = v; return this; }
        public Builder topic(String v) { this.topic = v; return this; }
        public Builder bootstrapServers(String v) { this.bootstrapServers = v; return this; }
        public Builder groupId(String v) { this.groupId = v; return this; }
        public Builder startingOffsets(String v) { this.startingOffsets = v; return this; }
        public Builder dataRowSchema(String v) { this.dataRowSchema = v; return this; }

        public KafkaJsonSource build() { return new KafkaJsonSource(this); }
    }
}
