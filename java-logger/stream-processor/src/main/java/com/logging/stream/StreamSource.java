package com.logging.stream;

/**
 * A Flink Table API source descriptor: emits a CREATE TABLE DDL string when
 * {@link #ddl()} is called. Implementations supply schema, format and connector
 * options so a {@link LogStreamProcessor} can register the table and run SQL
 * against it.
 *
 * Sources are responsible for:
 *   1. naming the table (matches what the SQL queries refer to),
 *   2. declaring fields that map to JSON paths inside the envelope,
 *   3. picking a Flink connector (kafka, filesystem, ...).
 */
public interface StreamSource {
    String tableName();
    String ddl();
}
