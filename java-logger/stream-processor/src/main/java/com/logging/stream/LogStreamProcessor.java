package com.logging.stream;

import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Composes a Flink Table API job: register one or more {@link StreamSource}
 * tables, register one or more {@link StreamSink} tables, then submit a
 * collection of INSERT statements as a single statement set so the planner
 * fuses them into a shared execution graph.
 *
 * The processor is unaware of the underlying transport — Kafka, NATS (via the
 * sidecar bridging to Kafka), or filesystem. It only cares that the source
 * descriptor produces a table whose schema matches the SELECT.
 */
public final class LogStreamProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(LogStreamProcessor.class);

    private final List<StreamSource> sources = new ArrayList<>();
    private final List<StreamSink> sinks = new ArrayList<>();
    private final List<String> inserts = new ArrayList<>();
    private final List<String> rawDdl = new ArrayList<>();

    public LogStreamProcessor addSource(StreamSource source) { sources.add(source); return this; }
    public LogStreamProcessor addSink(StreamSink sink) { sinks.add(sink); return this; }
    public LogStreamProcessor addInsert(String insertSql) { inserts.add(insertSql); return this; }
    public LogStreamProcessor addRawDdl(String ddl) { rawDdl.add(ddl); return this; }

    /** Submit the assembled job. Returns the TableResult for caller-driven lifecycle. */
    public TableResult execute() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        StreamTableEnvironment t = StreamTableEnvironment.create(
                env, EnvironmentSettings.newInstance().inStreamingMode().build());
        return executeOn(t);
    }

    /** Submit using a caller-supplied table environment — used by tests. */
    public TableResult executeOn(StreamTableEnvironment t) {
        for (StreamSource s : sources) {
            String ddl = s.ddl();
            LOG.info("Registering source table {}", s.tableName());
            LOG.debug("  DDL:\n{}", ddl);
            t.executeSql(ddl);
        }
        for (StreamSink s : sinks) {
            String ddl = s.ddl();
            LOG.info("Registering sink table {}", s.tableName());
            LOG.debug("  DDL:\n{}", ddl);
            t.executeSql(ddl);
        }
        for (String ddl : rawDdl) {
            LOG.debug("Raw DDL:\n{}", ddl);
            t.executeSql(ddl);
        }
        if (inserts.isEmpty()) {
            throw new IllegalStateException("No INSERT statements registered — nothing to execute");
        }
        if (inserts.size() == 1) {
            LOG.info("Executing single insert");
            return t.executeSql(inserts.get(0));
        }
        org.apache.flink.table.api.StatementSet set = t.createStatementSet();
        for (String insert : inserts) {
            set.addInsertSql(insert);
        }
        LOG.info("Executing statement set of {} inserts", inserts.size());
        return set.execute();
    }
}
