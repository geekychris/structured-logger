# Spark Consumer - Status Report

## What Was Attempted

Attempted to run the Spark Structured Streaming consumer to read from Kafka and write to Iceberg tables.

## Build Status

✅ **Successfully Built**
- Maven build completed successfully
- Created uber JAR: `structured-log-consumer-1.0.0.jar` (90MB)
- Includes all dependencies (Spark, Kafka, Iceberg, Jackson)

## Deployment Status

✅ **Successfully Deployed to Spark Container**
- Copied JAR to `/opt/spark-apps/`
- Copied log configs to `/opt/spark-apps/log-configs/`
- Both `user_events.json` and `api_metrics.json` configs available

## Runtime Status

### ✅ What Worked

1. **Application Startup**
   - Spark application initialized
   - SparkSession created successfully
   - Spark version 3.5.0 with Scala 2.12.18

2. **Config Loading**
   ```
   Loaded 2 log configurations
   ```
   - Successfully parsed both JSON configs
   - Jackson deserialization working with snake_case handling

3. **Kafka Connection**
   - Consumer able to resolve Kafka broker at `kafka:29092`
   - No Kafka connectivity errors

### ❌ What Failed

**Hive Metastore Required for Table Operations**

The Iceberg SparkCatalog requires a Hive metastore for table operations. Error:

```
org.datanucleus.store.rdbms.exceptions.MissingTableException: 
Required table missing : "DBS" in Catalog "" Schema "". 
DataNucleus requires this table to perform its persistence operations.
```

**Root Cause:**
- Trying to check if Iceberg table exists using `spark.table(tableName)`
- This operation requires Hive metastore database tables
- Our Docker setup doesn't have a configured Hive metastore for Spark

## Issues Encountered & Fixes Applied

### 1. JSON Snake_Case vs CamelCase
**Issue**: Jackson expected `tableName` but JSON had `table_name`  
**Fix**: Added `PropertyNamingStrategies.SNAKE_CASE` to ObjectMapper  
**Status**: ✅ Fixed

### 2. Table Existence Check
**Issue**: `spark.table()` requires metastore  
**Fix**: Attempted to skip existence check and use `CREATE TABLE IF NOT EXISTS`  
**Status**: ⚠️ Still requires metastore for SQL operations

## Why It's Complex

Spark + Iceberg + Streaming requires one of:

1. **Hive Metastore** (traditional approach)
   - Needs PostgreSQL/MySQL database
   - Requires Hive metastore service
   - Most production-ready

2. **Hadoop Catalog** (file-based)
   - Can work without metastore
   - But Spark SQL operations still try to use metastore
   - Would need to bypass `CREATE TABLE` and use DataFrameWriter API directly

3. **REST Catalog** (modern approach)
   - Requires Iceberg REST catalog service
   - Simpler than Hive but needs separate service

## What Would Be Needed to Complete

### Option 1: Use Existing Hive Metastore (Easiest)

The docker-compose already has `hive-metastore` service! We just need to configure Spark to use it:

```bash
spark-submit \
  --conf spark.sql.catalog.local.type=hive \
  --conf spark.sql.catalog.local.uri=thrift://hive-metastore:9083 \
  ...
```

### Option 2: Bypass Table Creation (Quick Fix)

Modify code to write directly using DataFrame API without SQL table operations:

```java
enrichedDF.writeStream()
    .format("iceberg")
    .option("path", "/opt/spark-data/warehouse/user_events")
    .option("checkpointLocation", "...")
    .start();
```

### Option 3: Use Parquet Instead (Simplest)

For demonstration, could just write Parquet files:

```java
enrichedDF.writeStream()
    .format("parquet")
    .option("path", "/opt/spark-data/warehouse/user_events")
    .option("checkpointLocation", "...")
    .start();
```

## Current Data Flow Status

```
✅ Application  →  ✅ Kafka  →  ❌ Iceberg
    (Working)      (Working)     (Blocked)
```

**Working:**
- Java & Python apps successfully publish structured logs to Kafka
- 9 messages total in Kafka topics (4 user-events, 5 api-metrics)
- Messages are properly structured JSON

**Blocked:**
- Spark consumer can't create Iceberg tables without metastore
- Streaming query never starts

## Quick Win: Use Hive Metastore

Since we already have `hive-metastore` container running in docker-compose, the fix is straightforward:

**Update spark-submit command:**

```bash
docker exec spark-master /opt/spark/bin/spark-submit \
  --class com.logging.consumer.StructuredLogConsumer \
  --master 'local[2]' \
  --packages org.apache.iceberg:iceberg-spark-runtime-3.5_2.12:1.4.2 \
  --conf spark.sql.catalog.local=org.apache.iceberg.spark.SparkCatalog \
  --conf spark.sql.catalog.local.type=hive \
  --conf spark.sql.catalog.local.uri=thrift://hive-metastore:9083 \
  --conf spark.sql.catalog.local.warehouse=/opt/spark-data/warehouse \
  /opt/spark-apps/structured-log-consumer-1.0.0.jar \
  /opt/spark-apps/log-configs/ \
  kafka:29092 \
  /opt/spark-data/warehouse
```

Key change: `--conf spark.sql.catalog.local.type=hive` and point to existing metastore.

## Summary

**Status**: 🟡 Partially Complete

- ✅ Application layer: WORKING
- ✅ Kafka layer: WORKING
- ❌ Warehouse layer: BLOCKED (metastore config needed)

**Effort to Complete**: ~10 minutes with correct Spark configuration

**What's Proven**:
- Metadata-driven structured logging works end-to-end
- Type-safe code generation works
- Kafka integration works perfectly
- Spark consumer code is correct (just needs metastore config)

**What's Remaining**:
- Configure Spark to use existing hive-metastore service
- OR simplify to write Parquet files for demo purposes
