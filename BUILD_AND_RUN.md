# Build and Run Guide

This guide provides complete instructions for building, configuring, and running the Structured Logging System.

## Overview

The Structured Logging System is a metadata-driven streaming log ingestion platform that:
- Generates type-safe loggers from JSON configurations
- Streams logs to Kafka topics
- Consumes logs with Spark Streaming
- Writes to **Apache Iceberg** tables stored in S3/MinIO (Parquet format)
- Supports querying via Trino/Presto

## Architecture

```
Applications → Kafka → Spark Consumer → Iceberg Tables (S3/MinIO)
                                              ↓
                                       Trino/Presto Queries
```

**Technology Stack**:
- **Kafka**: Message streaming
- **Spark Streaming**: Stream processing (Java)
- **Apache Iceberg**: Table format with ACID transactions and schema evolution
- **MinIO/S3**: Object storage for Iceberg data and metadata
- **Parquet**: Columnar file format for data files
- **Trino**: SQL query engine

## Prerequisites

- Docker and Docker Compose
- Java 11+ (for building)
- Maven 3.6+
- Python 3.8+ (for examples)

## Building the System

### 1. Build the Spark Consumer

The Spark consumer is a Java application that reads from Kafka and writes to Iceberg tables.

```bash
cd structured-logging/spark-consumer

# Clean and build
mvn clean package -DskipTests

# Copy JAR to the location accessible by Docker
cp target/structured-log-consumer-1.0.0.jar ../../../spark-apps-java/
```

**Output**: A fat JAR (~90MB) containing all dependencies including:
- Spark 3.4.1
- Iceberg 1.4.2
- Kafka clients
- Hadoop AWS
- Jackson

### 2. Build Logger Libraries

#### Java Logger

```bash
cd structured-logging/java-logger
mvn clean install

# The JAR is now in your local Maven repository
# Applications can depend on it via:
# <dependency>
#   <groupId>com.logging</groupId>
#   <artifactId>structured-logger</artifactId>
#   <version>1.0.0</version>
# </dependency>
```

#### Python Logger

```bash
cd structured-logging/python-logger
pip install -e .

# Or install requirements directly:
pip install -r requirements.txt
```

## Creating Log Configurations

Log configurations are JSON files that define structured log schemas, Kafka topics, and Iceberg table properties.

### Configuration Schema

Create a JSON file (e.g., `my_logs.json`) in `structured-logging/log-configs/`:

```json
{
  "name": "MyApplicationLogs",
  "version": "1.0.0",
  "description": "Logs for my application",
  "kafka": {
    "topic": "my-app-logs",
    "partitions": 3,
    "replication_factor": 1,
    "retention_ms": 604800000
  },
  "warehouse": {
    "table_name": "analytics.logs.my_app_logs",
    "partition_by": ["log_date", "log_level"],
    "sort_by": ["timestamp"],
    "retention_days": 90
  },
  "fields": [
    {
      "name": "timestamp",
      "type": "timestamp",
      "required": true,
      "description": "When the log occurred"
    },
    {
      "name": "log_level",
      "type": "string",
      "required": true,
      "description": "Log severity: INFO, WARN, ERROR"
    },
    {
      "name": "message",
      "type": "string",
      "required": true,
      "description": "Log message"
    },
    {
      "name": "user_id",
      "type": "string",
      "required": false,
      "description": "User who triggered the log"
    },
    {
      "name": "metadata",
      "type": "map<string,string>",
      "required": false,
      "description": "Additional key-value pairs"
    }
  ]
}
```

### Supported Field Types

- **Primitives**: `string`, `int`, `long`, `float`, `double`, `boolean`
- **Temporal**: `timestamp`, `date`
- **Complex**: `array<type>`, `map<string,string>`

### Partition Strategy

Choose partition fields based on query patterns:
- **Time-based**: `event_date`, `year`, `month`, `day`
- **Categorical**: `event_type`, `service_name`, `region`, `log_level`
- **Avoid**: High-cardinality fields (user_id, session_id)

### Retention and Storage

```json
{
  "warehouse": {
    "retention_days": 90,
    "sort_by": ["timestamp"],
    "partition_by": ["event_date", "event_type"]
  }
}
```

## Generating Type-Safe Loggers

After creating a log configuration, generate type-safe logger classes:

```bash
cd structured-logging

# Generate loggers for all configs
python generators/generate_loggers.py \
  --config-dir log-configs \
  --java-output generated-loggers/java \
  --python-output generated-loggers/python

# Or generate for a single config
python generators/generate_loggers.py \
  --config log-configs/my_logs.json \
  --java-output generated-loggers/java \
  --python-output generated-loggers/python
```

**Generated Files**:
- **Java**: `generated-loggers/java/com/logging/generated/MyApplicationLogsLogger.java`
- **Python**: `generated-loggers/python/my_application_logs_logger.py`

### Using Generated Loggers

#### Java Example

```java
import com.logging.generated.MyApplicationLogsLogger;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class MyApp {
    public static void main(String[] args) {
        // Initialize logger
        MyApplicationLogsLogger logger = new MyApplicationLogsLogger(
            "localhost:9092"  // Kafka bootstrap servers
        );

        // Log an event
        Map<String, String> metadata = new HashMap<>();
        metadata.put("request_id", "abc-123");
        metadata.put("ip_address", "192.168.1.1");

        logger.log(
            Instant.now(),
            "INFO",
            "User logged in successfully",
            "user-456",
            metadata
        );

        // Close when done
        logger.close();
    }
}
```

#### Python Example

```python
from generated.my_application_logs_logger import MyApplicationLogsLogger
from datetime import datetime

# Initialize logger
logger = MyApplicationLogsLogger(bootstrap_servers='localhost:9092')

# Log an event
logger.log(
    timestamp=datetime.now(),
    log_level='INFO',
    message='User logged in successfully',
    user_id='user-456',
    metadata={'request_id': 'abc-123', 'ip_address': '192.168.1.1'}
)

# Close when done
logger.close()
```

## Running the Spark Consumer

### Quick Start with S3/MinIO

```bash
cd structured-logging

# Start the consumer (uses S3/MinIO for Iceberg storage)
./start-consumer-s3.sh
```

### Manual Start

For more control or different configurations:

```bash
docker exec -d spark-master bash -c "AWS_REGION=us-east-1 \
  AWS_ACCESS_KEY_ID=admin \
  AWS_SECRET_ACCESS_KEY=password123 \
  nohup /opt/spark/bin/spark-submit \
  --master 'local[*]' \
  --packages org.apache.hadoop:hadoop-aws:3.3.4,com.amazonaws:aws-java-sdk-bundle:1.12.262,software.amazon.awssdk:bundle:2.17.257,software.amazon.awssdk:url-connection-client:2.17.257 \
  --conf spark.sql.catalog.local=org.apache.iceberg.spark.SparkCatalog \
  --conf spark.sql.catalog.local.warehouse=s3a://warehouse/ \
  --conf 'spark.sql.catalog.local.io-impl=org.apache.iceberg.aws.s3.S3FileIO' \
  --conf 'spark.sql.catalog.local.s3.endpoint=http://minio:9000' \
  --conf 'spark.sql.catalog.local.s3.path-style-access=true' \
  --conf spark.hadoop.fs.s3a.endpoint=http://minio:9000 \
  --conf spark.hadoop.fs.s3a.access.key=admin \
  --conf spark.hadoop.fs.s3a.secret.key=password123 \
  --conf spark.hadoop.fs.s3a.path.style.access=true \
  --conf 'spark.hadoop.fs.s3a.impl=org.apache.hadoop.fs.s3a.S3AFileSystem' \
  --conf spark.hadoop.fs.s3a.endpoint.region=us-east-1 \
  --conf spark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions \
  --class com.logging.consumer.StructuredLogConsumer \
  /opt/spark-apps-java/structured-log-consumer-1.0.0.jar \
  /opt/spark-apps/log-configs \
  kafka:29092 \
  s3a://warehouse/ \
  s3a://warehouse/checkpoints > /opt/spark-apps/consumer-s3.log 2>&1 &"
```

### Verify Consumer is Running

```bash
# Check process
docker exec spark-master ps aux | grep StructuredLogConsumer

# Check logs
docker exec spark-master tail -f /opt/spark-apps/consumer-s3.log

# Look for these messages:
# "Loaded 2 log configurations"
# "Started streaming query for UserEvents"
# "Started 2 streaming queries"
```

## Testing the System

### Send Test Messages

```bash
cd structured-logging/examples

# Run Python examples
python3 python_example.py

# Or run Java examples
javac -cp "../java-logger/target/*:generated/*" JavaExample.java
java -cp "../java-logger/target/*:generated/*:." JavaExample
```

### Verify Data in S3/MinIO

```bash
# List all Iceberg tables
docker exec minio mc ls myminio/warehouse/analytics/logs/

# View user_events data
docker exec minio mc ls myminio/warehouse/analytics/logs/user_events/ --recursive

# View api_metrics data
docker exec minio mc ls myminio/warehouse/analytics/logs/api_metrics/ --recursive
```

### Check Iceberg Metadata

```bash
# Metadata files show schema versions and snapshots
docker exec minio mc ls myminio/warehouse/analytics/logs/user_events/metadata/

# Output shows:
# v1.gz.metadata.json  - Initial table metadata
# v2.gz.metadata.json  - After first write
# v3.gz.metadata.json  - After subsequent writes
# snap-*.avro          - Snapshot manifests
# version-hint.text    - Current version pointer
```

### Inspect Parquet Files

Parquet files are organized by partition:

```bash
# List partitioned data
docker exec minio mc ls myminio/warehouse/analytics/logs/user_events/data/

# Example structure:
# event_date=2025-11-25/
#   event_type=click/
#     00001-xxx.parquet
#   event_type=page_view/
#     00002-xxx.parquet
#   event_type=purchase/
#     00003-xxx.parquet
```

## Querying with Trino

### Connect to Trino

```bash
docker exec -it trino trino
```

### Configure Iceberg Catalog (if not already configured)

Create `/etc/trino/catalog/iceberg.properties`:

```properties
connector.name=iceberg
iceberg.catalog.type=hive_metastore
hive.metastore.uri=thrift://hive-metastore:9083
hive.s3.endpoint=http://minio:9000
hive.s3.path-style-access=true
hive.s3.aws-access-key=admin
hive.s3.aws-secret-key=password123
```

### Example Trino Queries

#### Show Available Tables

```sql
-- Show all catalogs
SHOW CATALOGS;

-- Show schemas in iceberg catalog
SHOW SCHEMAS IN iceberg;

-- Show tables
SHOW TABLES IN iceberg.analytics_logs;
```

#### Query User Events

```sql
-- Select all user events
SELECT * 
FROM iceberg.analytics.logs.user_events 
LIMIT 10;

-- Count events by type
SELECT 
    event_type,
    COUNT(*) as event_count
FROM iceberg.analytics.logs.user_events
GROUP BY event_type
ORDER BY event_count DESC;

-- Filter by date and type
SELECT 
    user_id,
    page_url,
    timestamp
FROM iceberg.analytics.logs.user_events
WHERE event_date = DATE '2025-11-25'
  AND event_type = 'page_view'
ORDER BY timestamp DESC;

-- Time-series analysis
SELECT 
    date_trunc('hour', timestamp) as hour,
    event_type,
    COUNT(*) as events
FROM iceberg.analytics.logs.user_events
WHERE event_date >= DATE '2025-11-25'
GROUP BY 1, 2
ORDER BY 1, 2;
```

#### Query API Metrics

```sql
-- Average response time by service
SELECT 
    service_name,
    AVG(response_time) as avg_response_ms,
    COUNT(*) as request_count
FROM iceberg.analytics.logs.api_metrics
WHERE metric_date = DATE '2025-11-25'
GROUP BY service_name
ORDER BY avg_response_ms DESC;

-- Error rate by endpoint
SELECT 
    endpoint,
    service_name,
    SUM(CASE WHEN status_code >= 400 THEN 1 ELSE 0 END) * 100.0 / COUNT(*) as error_rate_pct,
    COUNT(*) as total_requests
FROM iceberg.analytics.logs.api_metrics
WHERE metric_date >= DATE '2025-11-25'
GROUP BY endpoint, service_name
HAVING COUNT(*) > 10
ORDER BY error_rate_pct DESC;

-- P95 response time
SELECT 
    service_name,
    approx_percentile(response_time, 0.95) as p95_response_time
FROM iceberg.analytics.logs.api_metrics
WHERE metric_date = DATE '2025-11-25'
GROUP BY service_name;
```

#### Iceberg-Specific Features

```sql
-- View table snapshots (time travel)
SELECT * 
FROM iceberg.analytics.logs.user_events.snapshots
ORDER BY committed_at DESC;

-- Query historical snapshot
SELECT COUNT(*) 
FROM iceberg.analytics.logs.user_events 
FOR VERSION AS OF 7864623715751563766;

-- View table schema evolution
SELECT * 
FROM iceberg.analytics.logs.user_events.history;

-- Check table partitions
SELECT * 
FROM iceberg.analytics.logs.user_events.partitions;

-- View table files
SELECT 
    partition,
    file_path,
    file_size_in_bytes / 1024 / 1024 as size_mb,
    record_count
FROM iceberg.analytics.logs.user_events.files
ORDER BY file_size_in_bytes DESC;
```

#### Join Multiple Tables

```sql
-- Correlate user behavior with API performance
SELECT 
    u.user_id,
    u.event_type,
    a.endpoint,
    a.response_time,
    a.status_code
FROM iceberg.analytics.logs.user_events u
JOIN iceberg.analytics.logs.api_metrics a
  ON u.timestamp = a.timestamp
WHERE u.event_date = DATE '2025-11-25'
  AND a.metric_date = DATE '2025-11-25'
LIMIT 100;
```

#### Create Views

```sql
-- Create a view for monitoring
CREATE VIEW iceberg.analytics.logs.api_health AS
SELECT 
    service_name,
    date_trunc('minute', timestamp) as minute,
    AVG(response_time) as avg_response_time,
    MAX(response_time) as max_response_time,
    COUNT(*) as request_count,
    SUM(CASE WHEN status_code >= 500 THEN 1 ELSE 0 END) as error_count
FROM iceberg.analytics.logs.api_metrics
WHERE metric_date >= current_date - INTERVAL '1' DAY
GROUP BY 1, 2;

-- Query the view
SELECT * 
FROM iceberg.analytics.logs.api_health
WHERE error_count > 0
ORDER BY minute DESC;
```

## Monitoring

### Consumer Health

```bash
# View streaming progress
docker exec spark-master tail -f /opt/spark-apps/consumer-s3.log | grep "Streaming query made progress"

# Check for errors
docker exec spark-master tail -100 /opt/spark-apps/consumer-s3.log | grep -E "(ERROR|Exception)"
```

### Kafka Topics

```bash
# List topics
docker exec kafka kafka-topics --bootstrap-server localhost:9092 --list

# Check topic details
docker exec kafka kafka-topics --bootstrap-server localhost:9092 --describe --topic user-events

# View messages
docker exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic user-events \
  --from-beginning \
  --max-messages 10
```

### Storage Metrics

```bash
# Check S3 bucket size
docker exec minio mc du myminio/warehouse/

# Count Parquet files
docker exec minio mc ls myminio/warehouse/analytics/logs/ --recursive | grep ".parquet" | wc -l

# View recent files
docker exec minio mc ls myminio/warehouse/analytics/logs/user_events/data/ --recursive --summarize
```

## Troubleshooting

### Consumer Won't Start

1. **Check if JAR exists**:
   ```bash
   docker exec spark-master ls -lh /opt/spark-apps-java/structured-log-consumer-1.0.0.jar
   ```

2. **Verify config directory**:
   ```bash
   docker exec spark-master ls -la /opt/spark-apps/log-configs/
   ```

3. **Check Kafka connectivity**:
   ```bash
   docker exec spark-master nc -zv kafka 29092
   ```

### No Data in Tables

1. **Verify messages in Kafka**:
   ```bash
   docker exec kafka kafka-console-consumer \
     --bootstrap-server localhost:9092 \
     --topic user-events \
     --from-beginning \
     --max-messages 5
   ```

2. **Check consumer is processing**:
   ```bash
   docker exec spark-master tail -50 /opt/spark-apps/consumer-s3.log | grep "Started streaming"
   ```

3. **Verify S3 write permissions**:
   ```bash
   docker exec minio mc ls myminio/warehouse/
   ```

### Trino Can't Read Tables

1. **Check Hive metastore**:
   ```bash
   docker exec -it trino trino --execute "SHOW SCHEMAS IN iceberg"
   ```

2. **Verify S3 configuration** in `/etc/trino/catalog/iceberg.properties`

3. **Test S3 connectivity from Trino**:
   ```bash
   docker exec trino curl -I http://minio:9000/minio/health/live
   ```

## Next Steps

1. **Create Custom Log Configs**: Define schemas for your application logs
2. **Generate Loggers**: Run the generator to create type-safe logger classes
3. **Integrate into Applications**: Use generated loggers in your Java/Python apps
4. **Build Dashboards**: Connect BI tools to Trino for visualization
5. **Set Up Alerts**: Query Iceberg tables for monitoring and alerting
6. **Scale to Production**: Follow `S3_DEPLOYMENT.md` for production deployment

## Additional Resources

- **S3 Deployment**: See `S3_DEPLOYMENT.md` for S3/MinIO configuration details
- **Schema Reference**: See `config-schema/log-config-schema.json` for full schema
- **Architecture**: See `ARCHITECTURE.md` for system design details
- **Apache Iceberg Docs**: https://iceberg.apache.org/docs/latest/
- **Trino Iceberg Connector**: https://trino.io/docs/current/connector/iceberg.html
