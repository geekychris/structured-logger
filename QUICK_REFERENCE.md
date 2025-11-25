# Quick Reference Guide

This is a quick reference for common tasks in the Structured Logging System.

## System Status

### Check if Consumer is Running

```bash
docker exec spark-master ps aux | grep StructuredLogConsumer
```

### View Consumer Logs

```bash
# Tail logs in real-time
docker exec spark-master tail -f /opt/spark-apps/consumer-s3.log

# Last 50 lines
docker exec spark-master tail -50 /opt/spark-apps/consumer-s3.log

# Look for errors
docker exec spark-master tail -100 /opt/spark-apps/consumer-s3.log | grep -E "(ERROR|Exception)"
```

### Check S3 Data

```bash
# List all tables
docker exec minio mc ls myminio/warehouse/analytics/logs/

# Count Parquet files
docker exec minio mc ls myminio/warehouse/analytics/logs/ --recursive | grep ".parquet" | wc -l

# View specific table data
docker exec minio mc ls myminio/warehouse/analytics/logs/user_events/ --recursive

# Check bucket size
docker exec minio mc du myminio/warehouse/
```

## Starting/Stopping

### Start Consumer

```bash
cd /path/to/structured-logging
./start-consumer-s3.sh
```

### Stop Consumer

```bash
# Find process ID
docker exec spark-master ps aux | grep StructuredLogConsumer

# Kill the process (replace PID)
docker exec spark-master kill <PID>
```

### Restart Consumer

```bash
# Kill existing
docker exec spark-master pkill -f StructuredLogConsumer

# Start new
./start-consumer-s3.sh
```

## Building

### Rebuild Spark Consumer

```bash
cd spark-consumer
mvn clean package -DskipTests
cp target/structured-log-consumer-1.0.0.jar ../../../spark-apps-java/

# Then restart consumer
cd ..
docker exec spark-master pkill -f StructuredLogConsumer
./start-consumer-s3.sh
```

### Build Logger Libraries

```bash
# Java
cd java-logger
mvn clean install

# Python
cd ../python-logger
pip install -e .
```

## Creating New Log Configs

### 1. Create Config File

Create `log-configs/my_new_log.json`:

```json
{
  "name": "MyNewLog",
  "version": "1.0.0",
  "description": "Description of my log",
  "kafka": {
    "topic": "my-new-log",
    "partitions": 3,
    "replication_factor": 1,
    "retention_ms": 604800000
  },
  "warehouse": {
    "table_name": "analytics.logs.my_new_log",
    "partition_by": ["log_date"],
    "sort_by": ["timestamp"],
    "retention_days": 30
  },
  "fields": [
    {
      "name": "timestamp",
      "type": "timestamp",
      "required": true,
      "description": "Event timestamp"
    },
    {
      "name": "log_date",
      "type": "date",
      "required": true,
      "description": "Event date for partitioning"
    }
    // Add more fields...
  ]
}
```

### 2. Generate Loggers

```bash
cd structured-logging
python generators/generate_loggers.py \
  --config log-configs/my_new_log.json \
  --java-output generated-loggers/java \
  --python-output generated-loggers/python
```

### 3. Copy Config to Docker

```bash
# Copy to the location Spark consumer reads from
cp log-configs/my_new_log.json ../../../spark-apps/log-configs/
```

### 4. Restart Consumer

The consumer will automatically pick up the new config on restart:

```bash
docker exec spark-master pkill -f StructuredLogConsumer
./start-consumer-s3.sh
```

## Testing

### Send Test Messages

```bash
cd examples
python3 python_example.py
```

### Verify in Kafka

```bash
# List topics
docker exec kafka kafka-topics --bootstrap-server localhost:9092 --list

# Read messages from topic
docker exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic user-events \
  --from-beginning \
  --max-messages 10
```

### Verify in S3

```bash
# Check if data appeared (wait 10-15 seconds after sending)
docker exec minio mc ls myminio/warehouse/analytics/logs/user_events/data/ --recursive
```

## Querying with Trino

### Connect to Trino

```bash
docker exec -it trino trino
```

### Common Queries

```sql
-- Show catalogs
SHOW CATALOGS;

-- Show schemas
SHOW SCHEMAS IN iceberg;

-- Show tables
SHOW TABLES IN iceberg.analytics_logs;

-- Query user events
SELECT * FROM iceberg.analytics.logs.user_events LIMIT 10;

-- Count by event type
SELECT event_type, COUNT(*) 
FROM iceberg.analytics.logs.user_events 
GROUP BY event_type;

-- View table snapshots (time travel)
SELECT * FROM iceberg.analytics.logs.user_events.snapshots;

-- View data files
SELECT partition, file_path, record_count
FROM iceberg.analytics.logs.user_events.files;
```

## Monitoring

### Kafka Health

```bash
# List topics
docker exec kafka kafka-topics --bootstrap-server localhost:9092 --list

# Topic details
docker exec kafka kafka-topics \
  --bootstrap-server localhost:9092 \
  --describe \
  --topic user-events

# Consumer groups
docker exec kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --list
```

### Check Streaming Progress

```bash
# Look for progress messages
docker exec spark-master tail -f /opt/spark-apps/consumer-s3.log | \
  grep "Streaming query made progress"

# Check for active queries
docker exec spark-master tail -50 /opt/spark-apps/consumer-s3.log | \
  grep "Started.*streaming"
```

### MinIO Console

Open web browser: http://localhost:9001

- Username: `admin`
- Password: `password123`

Navigate to `warehouse` bucket to browse files.

## Troubleshooting

### Consumer Not Starting

```bash
# Check if JAR exists
docker exec spark-master ls -lh /opt/spark-apps-java/structured-log-consumer-1.0.0.jar

# Check config directory
docker exec spark-master ls -la /opt/spark-apps/log-configs/

# Check Kafka is reachable
docker exec spark-master nc -zv kafka 29092
```

### No Data in S3

1. **Check Kafka has messages**:
   ```bash
   docker exec kafka kafka-console-consumer \
     --bootstrap-server localhost:9092 \
     --topic user-events \
     --from-beginning \
     --max-messages 5
   ```

2. **Check consumer logs**:
   ```bash
   docker exec spark-master tail -50 /opt/spark-apps/consumer-s3.log
   ```

3. **Verify streaming queries started**:
   ```bash
   docker exec spark-master tail -100 /opt/spark-apps/consumer-s3.log | \
     grep "Started streaming"
   ```

### Trino Can't See Tables

1. **Check Hive metastore**:
   ```bash
   docker exec -it trino trino --execute "SHOW SCHEMAS IN iceberg"
   ```

2. **Verify Iceberg catalog config** exists at `/etc/trino/catalog/iceberg.properties`

3. **Test MinIO connectivity from Trino**:
   ```bash
   docker exec trino curl -I http://minio:9000/minio/health/live
   ```

## File Locations

### On Host Machine

- **Spark Consumer JAR**: `spark-apps-java/structured-log-consumer-1.0.0.jar`
- **Log Configs**: `structured-logging/log-configs/*.json`
- **Generated Loggers**: `structured-logging/generated-loggers/`
- **Startup Script**: `structured-logging/start-consumer-s3.sh`

### In Docker Containers

- **Spark Consumer JAR**: `/opt/spark-apps-java/structured-log-consumer-1.0.0.jar`
- **Log Configs**: `/opt/spark-apps/log-configs/*.json`
- **Consumer Logs**: `/opt/spark-apps/consumer-s3.log`
- **Trino Catalog Config**: `/etc/trino/catalog/iceberg.properties`

## Key Endpoints

- **Kafka**: localhost:9092
- **MinIO S3 API**: localhost:9000
- **MinIO Console**: http://localhost:9001 (admin/password123)
- **Trino**: localhost:8081
- **Spark Master UI**: localhost:8082
- **Hive Metastore**: localhost:9083 (Thrift)

## Important Notes

### Iceberg Table Format

This system uses **Apache Iceberg** for table management. Data is stored as:
- **Parquet files**: In S3/MinIO at `s3://warehouse/analytics/logs/<table>/data/`
- **Metadata files**: In S3/MinIO at `s3://warehouse/analytics/logs/<table>/metadata/`
- **Catalog metadata**: In Hive Metastore (PostgreSQL backend)

### Partitioning

Tables are partitioned based on the `partition_by` fields in the log config. Common patterns:
- Time-based: `event_date`, `year`, `month`
- Categorical: `event_type`, `service_name`, `log_level`

Partitioning improves query performance by allowing Trino to skip irrelevant data.

### Streaming Intervals

The Spark consumer processes data every **10 seconds** (configurable in code). This means:
- Logs sent to Kafka appear in Iceberg tables within 10-15 seconds
- Multiple micro-batches are processed if data rate is high
- Checkpoint data tracks progress for exactly-once processing

## Documentation

- **Complete Guide**: [BUILD_AND_RUN.md](BUILD_AND_RUN.md)
- **S3 Deployment**: [S3_DEPLOYMENT.md](S3_DEPLOYMENT.md)
- **Main README**: [README.md](README.md)
