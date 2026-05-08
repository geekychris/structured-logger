package com.logging.stream.sinks;

import com.logging.stream.StreamSink;

/**
 * Built-in 'print' sink — writes rows to stdout. Useful for demos and tests.
 * Schema must match what the SELECT projection produces.
 */
public final class PrintSink implements StreamSink {

    private final String tableName;
    private final String columns; // e.g. "(window_start TIMESTAMP_LTZ(3), event_type STRING, total BIGINT)"

    public PrintSink(String tableName, String columns) {
        this.tableName = tableName;
        this.columns = columns;
    }

    @Override
    public String tableName() {
        return tableName;
    }

    @Override
    public String ddl() {
        return "CREATE TABLE " + tableName + " " + columns + " WITH ('connector' = 'print')";
    }
}
