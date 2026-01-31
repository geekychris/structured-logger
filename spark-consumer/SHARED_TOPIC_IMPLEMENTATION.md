# Shared Topic Implementation Guide

## Overview

This document describes how to modify the Spark consumer to support multiple log classes sharing a single Kafka topic.

## Architecture

### Before (Current)
```
Config 1: UserActivityLog
  ↓
Kafka Topic: user-activity
  ↓
Spark Query 1 → analytics_logs.user_activity

Config 2: ApiMetrics
  ↓
Kafka Topic: api-metrics
  ↓
Spark Query 2 → analytics_logs.api_metrics
```

### After (Shared Topic)
```
Kafka Topic: application-logs
  ↓
Spark Query 1 (filter: _log_type="user_activity") → analytics_logs.user_activity
  ↓
Spark Query 2 (filter: _log_type="api_metrics") → analytics_logs.api_metrics
```

## Implementation Steps

### Step 1: Add `log_type` to JSON Config

```json
{
  "name": "UserActivityLog",
  "log_type": "user_activity",  // NEW: Used for routing
  "kafka": {
    "topic": "application-logs"  // Shared topic
  }
}
```

### Step 2: Modify Logger to Include `_log_type`

The generated logger must add `_log_type` to every message:

```java
// In BaseStructuredLogger.publish()
protected void publish(String key, Object logRecord) {
    try {
        // Wrap record with metadata
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("_log_type", this.logType);
        envelope.put("_log_class", this.loggerName);
        envelope.put("_version", this.version);
        envelope.put("data", logRecord);
        
        String jsonValue = OBJECT_MAPPER.writeValueAsString(envelope);
        // ... send to Kafka
    }
}
```

### Step 3: Modify Consumer to Filter by `_log_type`

**Changes to StructuredLogConsumer.java:**

```java
private static StreamingQuery processLogConfig(
        SparkSession spark,
        LogConfig config,
        String kafkaBootstrapServers,
        String checkpointPath,
        String warehousePath) throws TimeoutException {

    System.out.println("Processing log config: " + config.name + " -> " + config.warehouse.tableName);

    // Create schema from config (add _log_type, _log_class, _version to schema)
    StructType dataSchema = createSchema(config.fields);
    
    // Envelope schema with metadata
    StructType envelopeSchema = DataTypes.createStructType(Arrays.asList(
        DataTypes.createStructField("_log_type", DataTypes.StringType, false),
        DataTypes.createStructField("_log_class", DataTypes.StringType, false),
        DataTypes.createStructField("_version", DataTypes.StringType, false),
        DataTypes.createStructField("data", dataSchema, false)
    ));

    // Read from Kafka (shared topic)
    Dataset<Row> kafkaDF = spark.readStream()
            .format("kafka")
            .option("kafka.bootstrap.servers", kafkaBootstrapServers)
            .option("subscribe", config.kafka.topic)
            .option("startingOffsets", "latest")
            .option("failOnDataLoss", "false")
            .load();

    // Parse JSON with envelope schema
    Dataset<Row> parsedDF = kafkaDF
            .selectExpr("CAST(value AS STRING) as json_value")
            .select(from_json(col("json_value"), envelopeSchema).as("envelope"))
            .select("envelope.*");
    
    // Filter by log_type (THIS IS THE KEY CHANGE)
    String logType = config.logType != null ? config.logType : config.name.toLowerCase();
    Dataset<Row> filteredDF = parsedDF
            .filter(col("_log_type").equalTo(lit(logType)))
            .select("data.*");  // Extract business fields

    // Add processing metadata
    Dataset<Row> enrichedDF = filteredDF
            .withColumn("_ingestion_timestamp", current_timestamp())
            .withColumn("_ingestion_date", current_date());

    // Rest of the code remains the same...
    ensureTableExists(spark, config, warehousePath);
    
    String icebergTable = "local." + config.warehouse.tableName;
    System.out.println("Writing to Iceberg table: " + icebergTable);
    
    StreamingQuery query = enrichedDF.writeStream()
            .format("iceberg")
            .outputMode("append")
            .trigger(Trigger.ProcessingTime("10 seconds"))
            .option("path", icebergTable)
            .option("checkpointLocation", checkpointPath + "/" + config.name)
            .start();

    System.out.println("Started streaming query for " + config.name);
    return query;
}
```

**Add to LogConfig class:**

```java
public static class LogConfig {
    @JsonProperty("name")
    public String name;
    
    @JsonProperty("log_type")
    public String logType;  // NEW: For routing
    
    @JsonProperty("version")
    public String version;
    
    // ... rest of fields
}
```

## Message Format

### Before (Current)
```json
{
  "user_id": "12345",
  "timestamp": "2026-01-30T17:00:00Z",
  "event_type": "LOGIN"
}
```

### After (With Envelope)
```json
{
  "_log_type": "user_activity",
  "_log_class": "UserActivityLog",
  "_version": "1.0.0",
  "data": {
    "user_id": "12345",
    "timestamp": "2026-01-30T17:00:00Z",
    "event_type": "LOGIN"
  }
}
```

## Trade-offs

### Pros
- ✅ Fewer Kafka topics to manage
- ✅ Can add new log classes without creating topics
- ✅ Better resource utilization with shared partitions

### Cons
- ⚠️ Each query reads entire shared topic (then filters)
- ⚠️ More data shuffling in Kafka consumer
- ⚠️ Harder to troubleshoot (mixed messages in one topic)
- ⚠️ All log classes must have same retention

## Performance Considerations

### Kafka Consumer Efficiency

With N log classes sharing a topic:
- **N Kafka consumer groups** (one per Spark query)
- **Each consumer reads entire topic** (Kafka doesn't filter)
- **Filtering happens in Spark** after messages are consumed

**Example with 3 log classes:**
```
Kafka Topic: application-logs (1000 msg/sec)
  ├─ Consumer Group 1 reads 1000 msg/sec → filters for user_activity (300 msg/sec)
  ├─ Consumer Group 2 reads 1000 msg/sec → filters for api_metrics (400 msg/sec)
  └─ Consumer Group 3 reads 1000 msg/sec → filters for audit_log (300 msg/sec)

Total Kafka reads: 3000 msg/sec (3x amplification)
Total useful messages: 1000 msg/sec
```

### When Shared Topics Make Sense

✅ **Good for:**
- Many log classes (10+) with low volume (<100 msg/sec each)
- Related log types (all application logs, all metrics)
- Dynamic log class additions

❌ **Bad for:**
- Few log classes (2-3) with high volume
- Very different message sizes or patterns
- Strict latency requirements

## Alternative: Single Query with foreachBatch

For better efficiency, use a single query with `foreachBatch`:

```java
Dataset<Row> kafkaDF = spark.readStream()
    .format("kafka")
    .option("kafka.bootstrap.servers", kafkaBootstrapServers)
    .option("subscribe", "application-logs")
    .load();

kafkaDF.writeStream()
    .foreachBatch((batchDF, batchId) -> {
        // Route each _log_type to its table
        for (LogConfig config : configs) {
            String logType = config.logType;
            Dataset<Row> filtered = batchDF
                .filter(col("_log_type").equalTo(logType))
                .select("data.*");
            
            filtered.write()
                .format("iceberg")
                .mode("append")
                .save("local." + config.warehouse.tableName);
        }
    })
    .start();
```

**Pros:**
- Only one Kafka consumer group
- No read amplification

**Cons:**
- More complex error handling
- Manual checkpointing required
- Harder to debug

## Testing

1. **Send test messages:**
   ```bash
   # Message 1
   echo '{"_log_type":"user_activity","data":{...}}' | \
     docker exec -i kafka kafka-console-producer \
       --broker-list localhost:9092 --topic application-logs
   
   # Message 2
   echo '{"_log_type":"api_metrics","data":{...}}' | \
     docker exec -i kafka kafka-console-producer \
       --broker-list localhost:9092 --topic application-logs
   ```

2. **Verify routing:**
   ```sql
   SELECT COUNT(*) FROM iceberg.analytics_logs.user_activity;
   SELECT COUNT(*) FROM iceberg.analytics_logs.api_metrics;
   ```

## Recommendation

**Start with dedicated topics** (current approach) unless:
- You have 10+ log classes
- Most log classes have low volume
- You need dynamic log class addition

The complexity and performance overhead of shared topics is often not worth it for small deployments.
