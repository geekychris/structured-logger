package com.logging.stream.sinks;

import com.logging.stream.StreamSink;

/**
 * Flink filesystem sink writing JSON line files. Drop-in target when you want
 * to land aggregated streams onto disk for ad-hoc inspection or for the
 * sidecar to ship to object storage.
 */
public final class FilesystemJsonSink implements StreamSink {

    private final String tableName;
    private final String columns;
    private final String path;

    public FilesystemJsonSink(String tableName, String columns, String path) {
        this.tableName = tableName;
        this.columns = columns;
        this.path = path;
    }

    @Override
    public String tableName() {
        return tableName;
    }

    @Override
    public String ddl() {
        return "CREATE TABLE " + tableName + " " + columns + " WITH ("
                + "'connector' = 'filesystem',"
                + "'path' = '" + path + "',"
                + "'format' = 'json',"
                + "'sink.rolling-policy.file-size' = '4MB',"
                + "'sink.rolling-policy.rollover-interval' = '1 min'"
                + ")";
    }
}
