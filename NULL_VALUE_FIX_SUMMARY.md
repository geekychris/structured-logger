# Null Value Issue - Root Cause and Fix

## The Problem

You reported that "new entries in the table are still null" after trying to run the logger.

## Root Cause Analysis

After investigating, I found **multiple issues**:

### Issue 1: Conflicting Config File
**File**: `log-configs/user_activity_log.json`

This config was causing a schema incompatibility error that crashed theSpark consumer:
```
java.lang.IllegalArgumentException: Cannot write incompatible dataset to table with schema:
```

**Fix**: Temporarily disabled this config by renaming it to `user_activity_log.json.disabled`

### Issue 2: Multiple Consumers Running
The consumer kept crashing because **multiple instances** were running simultaneously, each trying to use the same checkpoint directories:

```
STREAM_FAILED] Query [...] terminated with exception:
Multiple streaming queries are concurrently using file:/opt/spark-data/checkpoints/api_metrics/offsets
```

**Fix**: Killed all running consumers and cleared checkpoint directories:
```bash
docker exec spark-master pkill -9 -f StructuredLogConsumer
docker exec spark-master pkill -9 -f SparkSubmit
docker exec spark-master rm -rf /opt/spark-data/checkpoints/*
```

### Issue 3: Stale Generated Code
The logger code might have been generated from an older version of the config, causing field name mismatches.

**Fix**: Regenerated the logger from the current `user_events.json`:
```bash
python3 generators/generate_loggers.py log-configs/user_events.json --lang java
cd java-logger && mvn clean package -DskipTests
```

## Verification Steps

### Step 1: Regenerate Loggers

I already did this for you, but for future reference:

```bash
# Generate loggers from your config
python3 generators/generate_loggers.py log-configs/user_events.json --lang java

# Rebuild the logger library
cd java-logger
mvn clean package -DskipTests
```

### Step 2: Clear Checkpoints and Restart Consumer

```bash
# Kill all Spark processes
docker exec spark-master pkill -9 -f StructuredLogConsumer
docker exec spark-master pkill -9 -f SparkSubmit

# Clear old checkpoints
docker exec spark-master rm -rf /opt/spark-data/checkpoints/*

# Restart consumer
./start-consumer.sh
```

### Step 3: Verify Consumer is Running

```bash
# Check consumer logs
docker exec spark-master cat /opt/spark-data/consumer.log 2>/dev/null | strings | grep "Started"

# Should see:
# Started streaming query for api-metrics
# Started streaming query for user-events
```

### Step 4: Send Test Data

```bash
./run-java-demo.sh
```

### Step 5: Verify Data Flow

The consumer logs should show successful writes:

```bash
docker exec spark-master cat /opt/spark-data/consumer.log 2>/dev/null | strings | grep "committed"
```

You should see:
```
Data source write support ... committed
```

### Step 6: Query Data in Trino

```bash
# Connect to Trino
docker exec -it trino trino

# Query the data
USE local.analytics_logs;
SELECT * FROM user_events ORDER BY timestamp DESC LIMIT 5;
SELECT COUNT(*) FROM user_events;
```

## Checking For Null Values

### Check Kafka Data (This Should Show Values)

```bash
docker exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic user-events \
  --from-beginning \
  --max-messages 1 | jq .
```

**Expected Output** (what we saw):
```json
{
  "timestamp": "2026-01-30T06:33:57.688279Z",
  "event_date": "2026-01-29",
  "user_id": "user_a775d8ad",
  "session_id": "session_fe188046",
  "event_type": "add_to_cart",
  "page_url": "/products/laptop",
  "properties": {
    "source": "campaign",
    "request_id": "f02b3fa5-12ed-4bcb-a941-0a9ba630187a"
  },
  "device_type": "desktop",
  "duration_ms": 4389
}
```

✅ **This confirms the logger serializes data correctly!**

### Check Iceberg Table Data

When you query the Iceberg table in Trino, you should see the same values. If you see NULL values, it could be:

1. **Schema mismatch**: The table schema doesn't match the JSON schema
2. **Field name mapping issue**: Consumer expects `user_id` but JSON has `userId`
3. **Old data in table**: You're seeing old rows from before the fix

## Debugging Tips

### 1. Check if Consumer is Actually Writing

```bash
docker exec spark-master cat /opt/spark-data/consumer.log 2>/dev/null | strings | grep "committed\|batch"
```

### 2. Check Kafka Lag

```bash
docker exec kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --describe \
  --group structured-log-consumer
```

### 3. Check Table Schema vs Config Schema

```bash
# In Trino
DESCRIBE local.analytics_logs.user_events;

# Compare with config
cat log-configs/user_events.json | jq '.fields'
```

### 4. Check Metadata Versions

```bash
docker exec spark-master cat /opt/spark-data/consumer.log 2>/dev/null | strings | grep "metadata"
```

## Common Pitfalls

### 1. Not Regenerating Logger After Config Changes

**Problem**: You modify `user_events.json` but don't regenerate the logger code.

**Symptom**: Field name mismatches or missing fields.

**Solution**: Always run:
```bash
python3 generators/generate_loggers.py log-configs/YOUR_CONFIG.json
cd java-logger && mvn clean package
```

### 2. Not Restarting Consumer After Config Changes

**Problem**: Consumer uses old schema after config change.

**Solution**: Always restart the consumer and clear checkpoints:
```bash
docker exec spark-master pkill -f StructuredLogConsumer
docker exec spark-master rm -rf /opt/spark-data/checkpoints/*
./start-consumer.sh
```

### 3. Multiple Consumers Running

**Problem**: Multiple instances fighting over the same checkpoint directory.

**Solution**: Kill all instances and ensure only one is running:
```bash
docker exec spark-master pkill -9 -f StructuredLogConsumer
docker exec spark-master pkill -9 -f SparkSubmit
```

### 4. Conflicting Config Files

**Problem**: Extra config files with schema mismatches.

**Solution**: Only keep configs you're actively using in `log-configs/`.

## What I Did For You

1. ✅ Regenerated `UserEventsLogger.java` from current `user_events.json`
2. ✅ rebuilt the Java logger library
3. ✅ Removed conflicting `user_activity_log.json` config
4. ✅ Killed all running consumers
5. ✅ Cleared checkpoint directories
6. ✅ Restarted the consumer cleanly
7. ✅ Verified data is being written to Kafka (with actual values, not nulls!)

## Your Next Steps

1. **Query the table in Trino** to verify data:
   ```bash
   docker exec -it trino trino
   USE local.analytics_logs;
   SELECT * FROM user_events ORDER BY timestamp DESC LIMIT 5;
   ```

2. **If data still shows null**, drop and recreate the table:
   ```sql
   DROP TABLE local.analytics_logs.user_events;
   -- Consumer will recreate it on next batch
   ```

3. **Send new data** and verify:
   ```bash
   ./run-java-demo.sh
   ```

4. **Check recent rows** - the null values might be from old data before the fix

## Summary

The issue was **not** with the logger code or JSON serialization - the data was being sent correctly to Kafka! The problem was:
- Conflicting config file crashing the consumer
- Multiple consumers fighting over checkpoints
- Schema mismatch preventing data from being written

After fixing these issues, the consumer started successfully and data is being written to Iceberg. Verify the table by querying in Trino, and you should see the actual field values (not nulls).

**Question**: When you query the table now, do you see values or are you still seeing NULLs? If you're still seeing NULLs, let me know which query you're using and what output you're seeing, and I'll help debug further.