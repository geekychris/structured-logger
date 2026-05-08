package com.logging.stream.sources;

import com.logging.stream.StreamSource;

/**
 * Flink filesystem source pointed at a directory of NDJSON files (matches the
 * {@link com.logging.sink.FileSink} output, or what the sidecar produces when
 * targeting FILE). Useful for replay and for unit tests that don't want a
 * Kafka broker.
 */
public final class FilesystemJsonSource implements StreamSource {

    private final String tableName;
    private final String path;
    private final String dataRowSchema;
    private final boolean monitor;

    public FilesystemJsonSource(String tableName, String path, String dataRowSchema, boolean monitor) {
        this.tableName = tableName;
        this.path = path;
        this.dataRowSchema = dataRowSchema;
        this.monitor = monitor;
    }

    @Override
    public String tableName() {
        return tableName;
    }

    @Override
    public String ddl() {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(tableName).append(" (\n");
        sb.append("  `_log_type`   STRING,\n");
        sb.append("  `_log_class`  STRING,\n");
        sb.append("  `_version`    STRING,\n");
        sb.append("  `data`        ").append(dataRowSchema).append("\n");
        sb.append(") WITH (\n");
        sb.append("  'connector' = 'filesystem',\n");
        sb.append("  'path' = '").append(path).append("',\n");
        sb.append("  'format' = 'json',\n");
        sb.append("  'json.ignore-parse-errors' = 'true'");
        if (monitor) {
            sb.append(",\n  'source.monitor-interval' = '1 s'");
        }
        sb.append("\n)");
        return sb.toString();
    }
}
