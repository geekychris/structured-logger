package com.example.flink;

import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;

/**
 * Tutorial example #1 — the "hello world" of the Table API.
 *
 * Generates synthetic order rows with the built-in datagen connector, runs an
 * aggregation through Table SQL, and prints the results to stdout (visible in
 * the TaskManager log under the Flink UI).
 *
 * Run from the Flink CLI:
 *   flink run -c com.example.flink.HelloTableApi <path-to-uber-jar>
 *
 * Or submit via the Web UI: http://127.0.0.1:18030/#/submit
 *
 * Set a breakpoint inside the .execute() lambda — the Table API plans your
 * query at submit time on the JobManager (port 18040) and then runs the
 * compiled operators on the TaskManager (port 18041). Most "what is my data
 * doing" debugging happens on the TaskManager.
 */
public final class HelloTableApi {

    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        StreamTableEnvironment tEnv = StreamTableEnvironment.create(
                env, EnvironmentSettings.newInstance().inStreamingMode().build());

        // 1) Source: built-in datagen → unbounded synthetic stream of orders.
        tEnv.executeSql(
            "CREATE TABLE orders ("
          + "  order_id   STRING,"
          + "  customer   STRING,"
          + "  amount_usd DECIMAL(10, 2),"
          + "  created_at TIMESTAMP_LTZ(3),"
          + "  WATERMARK FOR created_at AS created_at - INTERVAL '5' SECOND"
          + ") WITH ("
          + "  'connector' = 'datagen',"
          + "  'rows-per-second' = '20',"
          + "  'fields.order_id.length' = '8',"
          + "  'fields.customer.length' = '6',"
          + "  'fields.amount_usd.min' = '1.00',"
          + "  'fields.amount_usd.max' = '500.00'"
          + ")");

        // 2) Aggregation: revenue per 30-second tumbling window.
        Table windowed = tEnv.sqlQuery(
            "SELECT "
          + "  TUMBLE_START(created_at, INTERVAL '30' SECOND) AS window_start, "
          + "  COUNT(*)        AS n_orders, "
          + "  SUM(amount_usd) AS revenue_usd "
          + "FROM orders "
          + "GROUP BY TUMBLE(created_at, INTERVAL '30' SECOND)");

        // 3) Sink: print to TaskManager stdout (visible in the Web UI).
        tEnv.executeSql(
            "CREATE TABLE windowed_revenue ("
          + "  window_start TIMESTAMP_LTZ(3),"
          + "  n_orders     BIGINT,"
          + "  revenue_usd  DECIMAL(38, 2)"
          + ") WITH ('connector' = 'print')");

        windowed.executeInsert("windowed_revenue");
    }

    private HelloTableApi() {}
}
