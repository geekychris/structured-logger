package com.logging.stream;

/**
 * Flink Table API sink descriptor — counterpart of {@link StreamSource}. The
 * processor binds an INSERT INTO ... SELECT ... pipeline by registering both
 * descriptors with the planner.
 */
public interface StreamSink {
    String tableName();
    String ddl();
}
