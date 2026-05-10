package com.example.flink;

import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;

/**
 * Tutorial example #2 — read structured-logging envelopes from Kafka,
 * unwrap, aggregate per event_type, print.
 *
 * This consumes the SAME `user-events` topic the structured-logging
 * project's loggers publish to. Bring up the lakehouse stack first, then
 * run a producer (e.g. python3 test_e2e.py from the repo root) to generate
 * traffic, then submit this job.
 *
 * Run:
 *   flink run -c com.example.flink.KafkaToConsoleTableApi <path-to-uber-jar>
 */
public final class KafkaToConsoleTableApi {

    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        StreamTableEnvironment tEnv = StreamTableEnvironment.create(
                env, EnvironmentSettings.newInstance().inStreamingMode().build());

        // The structured-logging envelope is { _log_type, _log_class, _version,
        // data: { ... } }. We only care about a few fields under data — we let
        // Flink JSON decode the rest as needed.
        tEnv.executeSql(
            "CREATE TABLE user_events_raw ("
          + "  `_log_type`  STRING,"
          + "  `_log_class` STRING,"
          + "  `_version`   STRING,"
          + "  `data` ROW<"
          + "      `timestamp`   STRING,"
          + "      `event_date`  STRING,"
          + "      `user_id`     STRING,"
          + "      `session_id`  STRING,"
          + "      `event_type`  STRING,"
          + "      `device_type` STRING,"
          + "      `duration_ms` BIGINT"
          + "  >,"
          + "  proc_time AS PROCTIME()"
          + ") WITH ("
          + "  'connector'         = 'kafka',"
          + "  'topic'             = 'user-events',"
          + "  'properties.bootstrap.servers' = 'kafka:29092',"
          + "  'properties.group.id'          = 'flink-tutorial-table-api',"
          + "  'scan.startup.mode' = 'earliest-offset',"
          + "  'format'            = 'json',"
          + "  'json.fail-on-missing-field' = 'false',"
          + "  'json.ignore-parse-errors'   = 'true'"
          + ")");

        // Flatten the envelope and bucket by 1-minute proc-time windows.
        Table aggregated = tEnv.sqlQuery(
            "SELECT "
          + "  TUMBLE_START(proc_time, INTERVAL '1' MINUTE) AS window_start, "
          + "  data.event_type, "
          + "  data.device_type, "
          + "  COUNT(*)              AS n_events, "
          + "  AVG(data.duration_ms) AS avg_duration_ms "
          + "FROM user_events_raw "
          + "GROUP BY "
          + "  TUMBLE(proc_time, INTERVAL '1' MINUTE), "
          + "  data.event_type, data.device_type");

        tEnv.executeSql(
            "CREATE TABLE event_stats ("
          + "  window_start    TIMESTAMP_LTZ(3),"
          + "  event_type      STRING,"
          + "  device_type     STRING,"
          + "  n_events        BIGINT,"
          + "  avg_duration_ms DOUBLE"
          + ") WITH ('connector' = 'print')");

        aggregated.executeInsert("event_stats");
    }

    private KafkaToConsoleTableApi() {}
}
