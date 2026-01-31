# Debugging the Spark Consumer

## Quick Debug Commands

### Check if Consumer is Running

```bash
# Check process
docker exec spark-master ps aux | grep -E "(kafka-to-iceberg|StructuredLogConsumer)"

# Check logs (Python consumer)
docker exec spark-master tail -100 /opt/spark-data/consumer.log

# Check logs (Java consumer)
docker exec spark-master tail -100 /opt/spark-data/java-consumer.log
```

### View Consumer Logs in Real-Time

```bash
# Python consumer
docker exec spark-master tail -f /opt/spark-data/consumer.log

# Java consumer  
docker exec spark-master tail -f /opt/spark-data/java-consumer.log

# Filter for errors
docker exec spark-master tail -f /opt/spark-data/consumer.log | grep -i error
```

### Check Streaming Query Status

```bash
# Python - check progress
docker exec spark-master cat /opt/spark-data/consumer.log | grep -A 10 "numInputRows"

# Check checkpoint status
docker exec spark-master ls -la /opt/spark-data/checkpoints/
```

### Inspect Kafka Messages

```bash
# Get message count
docker exec kafka kafka-run-class kafka.tools.GetOffsetShell \
  --broker-list localhost:9092 --topic user-events --time -1

# Read latest message
docker exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic user-events \
  --max-messages 1 \
  --timeout-ms 3000

# Read from specific offset
docker exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic user-events \
  --partition 0 \
  --offset 100 \
  --max-messages 5
```

### Check Iceberg Tables

```bash
# List tables
docker exec trino trino --execute "SHOW TABLES FROM iceberg.analytics_logs"

# Check table schema
docker exec trino trino --execute "DESCRIBE iceberg.analytics_logs.user_events"

# Check row counts
docker exec trino trino --execute "
  SELECT COUNT(*) FROM iceberg.analytics_logs.user_events;
  SELECT COUNT(*) FROM iceberg.analytics_logs.api_metrics;
"

# View latest rows
docker exec trino trino --execute "
  SELECT * FROM iceberg.analytics_logs.user_events 
  ORDER BY _ingestion_timestamp DESC 
  LIMIT 5
"
```

## Common Issues and Fixes

### Issue: Consumer Not Processing Messages

**Symptoms:**
- Messages in Kafka but not in Iceberg
- Consumer log shows "idle and waiting"

**Debug Steps:**

```bash
# 1. Check if consumer is stuck
docker exec spark-master tail -20 /opt/spark-data/consumer.log

# 2. Check for exceptions
docker exec spark-master grep -i "exception\|error" /opt/spark-data/consumer.log | tail -20

# 3. Restart consumer
docker exec spark-master pkill -f kafka-to-iceberg
./start-consumer.sh
```

### Issue: Schema Mismatch Errors

**Symptoms:**
- "Cannot write incompatible dataset"
- "Problems: field cannot be promoted"

**Debug Steps:**

```bash
# 1. Check error message
docker exec spark-master grep "incompatible\|promoted" /opt/spark-data/consumer.log

# 2. Check table schema
docker exec trino trino --execute "DESCRIBE iceberg.analytics_logs.user_events"

# 3. Drop and recreate table
docker exec trino trino --execute "DROP TABLE iceberg.analytics_logs.user_events"
docker exec spark-master pkill -f kafka-to-iceberg
./start-consumer.sh
```

### Issue: Envelope Parse Errors

**Symptoms:**
- Null values in data
- "Missing envelope field" errors

**Debug Steps:**

```bash
# 1. Check actual message format
docker exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic user-events \
  --max-messages 1 | python3 -m json.tool

# 2. Verify envelope fields
./verify_envelope.sh

# 3. Rebuild logger with envelope
cd java-logger && mvn clean package && cd ..
```

## Debugging Tools

### Tool 1: Message Inspector

```bash
#!/bin/bash
# inspect-kafka-message.sh

TOPIC=${1:-user-events}
OFFSET=${2:-latest}

echo "Fetching message from topic: $TOPIC"

if [ "$OFFSET" = "latest" ]; then
    # Get latest offset
    LATEST=$(docker exec kafka kafka-run-class kafka.tools.GetOffsetShell \
      --broker-list localhost:9092 --topic $TOPIC --time -1 | cut -d: -f3)
    OFFSET=$((LATEST - 1))
fi

echo "Reading offset: $OFFSET"
docker exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic $TOPIC \
  --partition 0 \
  --offset $OFFSET \
  --max-messages 1 \
  2>/dev/null | python3 -c "
import sys, json
msg = json.loads(sys.stdin.read())
print(json.dumps(msg, indent=2))
print('\nEnvelope fields:')
for key in ['_log_type', '_log_class', '_version']:
    print(f'  {key}: {msg.get(key, \"MISSING\")}')
print(f'\nData keys: {list(msg.get(\"data\", {}).keys())}')
"
```

### Tool 2: Consumer Health Check

```bash
#!/bin/bash
# check-consumer-health.sh

echo "=== Consumer Health Check ==="
echo ""

# Check if running
echo "1. Process Status:"
if docker exec spark-master ps aux | grep -q kafka-to-iceberg; then
    echo "  ✓ Consumer is running"
else
    echo "  ✗ Consumer is NOT running"
fi
echo ""

# Check recent activity
echo "2. Recent Activity (last 10 lines):"
docker exec spark-master tail -10 /opt/spark-data/consumer.log | grep -v "idle"
echo ""

# Check for errors
echo "3. Recent Errors:"
ERROR_COUNT=$(docker exec spark-master grep -c -i "error\|exception" /opt/spark-data/consumer.log 2>/dev/null || echo 0)
if [ "$ERROR_COUNT" -gt 0 ]; then
    echo "  ⚠  Found $ERROR_COUNT errors"
    docker exec spark-master grep -i "error\|exception" /opt/spark-data/consumer.log | tail -5
else
    echo "  ✓ No errors found"
fi
echo ""

# Check Kafka lag
echo "4. Kafka Messages:"
for topic in user-events api-metrics; do
    OFFSET=$(docker exec kafka kafka-run-class kafka.tools.GetOffsetShell \
      --broker-list localhost:9092 --topic $topic --time -1 2>/dev/null | cut -d: -f3)
    echo "  $topic: $OFFSET messages"
done
echo ""

# Check Iceberg tables
echo "5. Iceberg Row Counts:"
docker exec trino trino --execute "
  SELECT 'user_events' as table, COUNT(*) as rows FROM iceberg.analytics_logs.user_events
  UNION ALL
  SELECT 'api_metrics', COUNT(*) FROM iceberg.analytics_logs.api_metrics
" 2>/dev/null | grep -v WARNING || echo "  ⚠  Could not query tables"
```

### Tool 3: Enable Verbose Logging

For Python consumer, add debug logging:

```python
# Edit spark-consumer/kafka-to-iceberg-consumer.py
# Add after SparkSession creation:

spark.sparkContext().setLogLevel("INFO")  # or "DEBUG" for more detail

# Add debug output in process_topic():
parsed_df.printSchema()  # Shows the schema being used
```

For Java consumer, modify log level:

```java
// In StructuredLogConsumer.java line 64
spark.sparkContext().setLogLevel("INFO");  // Change from "WARN" to "INFO" or "DEBUG"
```

## Interactive Debugging

### Start Consumer in Foreground (Not Daemon)

**Python Consumer:**
```bash
# Stop background consumer
docker exec spark-master pkill -f kafka-to-iceberg

# Run in foreground to see live output
docker exec -it spark-master bash -c "
  /opt/spark/bin/spark-submit \
    --master 'local[*]' \
    --packages org.apache.hadoop:hadoop-aws:3.3.4,com.amazonaws:aws-java-sdk-bundle:1.12.262,org.apache.spark:spark-sql-kafka-0-10_2.12:3.5.0,org.apache.iceberg:iceberg-spark-runtime-3.5_2.12:1.4.2,software.amazon.awssdk:bundle:2.20.18,software.amazon.awssdk:url-connection-client:2.20.18 \
    --conf spark.hadoop.fs.s3a.access.key=admin \
    --conf spark.hadoop.fs.s3a.secret.key=password123 \
    --conf spark.hadoop.fs.s3a.endpoint=http://minio:9000 \
    --conf spark.hadoop.fs.s3a.path.style.access=true \
    --conf spark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions \
    --conf spark.sql.catalog.iceberg=org.apache.iceberg.spark.SparkCatalog \
    --conf spark.sql.catalog.iceberg.type=hive \
    --conf spark.sql.catalog.iceberg.uri=thrift://hive-metastore:9083 \
    --conf spark.sql.catalog.iceberg.warehouse=s3a://warehouse/ \
    --conf spark.sql.catalog.iceberg.io-impl=org.apache.iceberg.aws.s3.S3FileIO \
    --conf spark.sql.catalog.iceberg.s3.endpoint=http://minio:9000 \
    --conf spark.sql.catalog.iceberg.s3.path-style-access=true \
    /opt/spark-apps/kafka-to-iceberg-consumer.py
"
```

**Java Consumer:**
```bash
# Build first
cd spark-consumer && mvn clean package && cd ..

# Copy JAR to container
docker cp spark-consumer/target/structured-log-consumer-1.0.0.jar spark-master:/opt/spark-apps/

# Run in foreground
docker exec -it spark-master bash -c "
  /opt/spark/bin/spark-submit \
    --master 'local[*]' \
    --class com.logging.consumer.StructuredLogConsumer \
    --packages org.apache.hadoop:hadoop-aws:3.3.4,com.amazonaws:aws-java-sdk-bundle:1.12.262,org.apache.spark:spark-sql-kafka-0-10_2.12:3.5.0,org.apache.iceberg:iceberg-spark-runtime-3.5_2.12:1.4.2 \
    --conf spark.hadoop.fs.s3a.access.key=admin \
    --conf spark.hadoop.fs.s3a.secret.key=password123 \
    --conf spark.hadoop.fs.s3a.endpoint=http://minio:9000 \
    --conf spark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions \
    --conf spark.sql.catalog.local=org.apache.iceberg.spark.SparkCatalog \
    --conf spark.sql.catalog.local.type=hive \
    --conf spark.sql.catalog.local.warehouse=s3a://warehouse/ \
    /opt/spark-apps/structured-log-consumer-1.0.0.jar \
    /opt/spark-apps/log-configs \
    kafka:29092 \
    s3a://warehouse
"
```

### Query Streaming State

```bash
# Check Spark UI (if accessible)
# Navigate to: http://localhost:4040

# Check streaming query details
docker exec spark-master cat /opt/spark-data/checkpoints/user_events/offsets/*/offsets.json 2>/dev/null

# Check committed offsets
docker exec kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --describe \
  --all-groups
```

## Performance Debugging

### Check Processing Lag

```bash
# Extract processing metrics from logs
docker exec spark-master grep "numInputRows" /opt/spark-data/consumer.log | tail -5

# Check batch processing time
docker exec spark-master grep "ProcessingTime" /opt/spark-data/consumer.log | tail -5
```

### Monitor Resource Usage

```bash
# Check Spark container resources
docker stats spark-master --no-stream

# Check Java heap usage (if Java consumer)
docker exec spark-master jstat -gc $(docker exec spark-master pgrep java | head -1)
```

## Cheat Sheet

| Task | Command |
|------|---------|
| **View logs** | `docker exec spark-master tail -f /opt/spark-data/consumer.log` |
| **Restart** | `docker exec spark-master pkill -f kafka-to-iceberg && ./start-consumer.sh` |
| **Check errors** | `docker exec spark-master grep -i error /opt/spark-data/consumer.log` |
| **Kafka messages** | `docker exec kafka kafka-console-consumer --bootstrap-server localhost:9092 --topic user-events --max-messages 1` |
| **Table count** | `docker exec trino trino --execute "SELECT COUNT(*) FROM iceberg.analytics_logs.user_events"` |
| **Drop table** | `docker exec trino trino --execute "DROP TABLE iceberg.analytics_logs.user_events"` |
| **Health check** | `./check-consumer-health.sh` |
| **Verify envelope** | `./verify_envelope.sh` |
