# Structured Logging Demo

Complete demonstration of the metadata-driven structured logging system with real-time streaming to Apache Iceberg tables.

## What This Demo Shows

This demo demonstrates a complete production-ready logging pipeline:

1. **Metadata-Driven Logger Generation** - Define log schemas in JSON, generate type-safe loggers
2. **Real-Time Streaming** - Kafka for reliable, high-throughput log ingestion
3. **Spark Streaming** - Micro-batch processing (10-second windows)
4. **Apache Iceberg Tables** - Modern lakehouse format with ACID transactions
5. **MinIO Storage** - S3-compatible object storage for Parquet files
6. **SQL Analytics** - Query logs using Trino

## Architecture

```
┌─────────────────┐
│   Application   │
│  (Java/Python)  │
└────────┬────────┘
         │ Type-safe logger
         ↓
┌─────────────────┐
│      Kafka      │ ← Message streaming & buffering
└────────┬────────┘
         │ Stream
         ↓
┌─────────────────┐
│ Spark Consumer  │ ← Real-time processing (10s batches)
└────────┬────────┘
         │ Write
         ↓
┌─────────────────┐
│ Iceberg Tables  │ ← ACID transactions, schema evolution
│   (in MinIO)    │    Parquet format, partitioned
└────────┬────────┘
         │ Query
         ↓
┌─────────────────┐
│     Trino       │ ← SQL analytics
└─────────────────┘
```

## Quick Start

### 1. Start Everything

```bash
./start-demo.sh
```

This will:
- Start all Docker services (Kafka, Spark, MinIO, Trino, etc.)
- Build the Spark consumer if needed
- Start the Spark streaming job
- Wait for all services to be healthy

### 2. Generate Loggers

```bash
make generate
```

This generates type-safe logger classes from the example configs in `examples/`.

### 3. Send Test Logs

#### Option A: Use provided examples

```bash
# Java example (build first)
cd examples
javac -cp ../java-logger/target/*:. JavaExample.java
java -cp ../java-logger/target/*:. JavaExample

# Python example
cd examples
python3 python_example.py
```

#### Option B: Write your own

**Java:**
```java
import com.logging.generated.UserEventsLogger;
import java.time.Instant;
import java.time.LocalDate;

try (UserEventsLogger logger = new UserEventsLogger()) {
    logger.log(
        Instant.now(),
        LocalDate.now(),
        "user123",
        "session456",
        "click",
        "/products/widget",
        null,
        "mobile",
        1500L
    );
}
```

**Python:**
```python
from structured_logging.generated.userevents_logger import UserEventsLogger
from datetime import datetime, date

with UserEventsLogger() as logger:
    logger.log(
        timestamp=datetime.utcnow(),
        event_date=date.today(),
        user_id="user123",
        session_id="session456",
        event_type="click",
        page_url="/products/widget",
        device_type="mobile",
        duration_ms=1500
    )
```

### 4. Verify Logs Are Flowing

**Check Spark Consumer Logs:**
```bash
docker exec spark-master tail -f /opt/spark-data/consumer.log
```

You should see:
```
Loaded log configuration: UserEvents
Processing batch... rows read: 5
Successfully wrote 5 rows to analytics.logs.user_events
```

**Check Kafka Topics:**
```bash
docker exec kafka kafka-topics --bootstrap-server localhost:9092 --list
docker exec kafka kafka-console-consumer --bootstrap-server localhost:9092 --topic user-events --from-beginning --max-messages 5
```

### 5. Query Logs with Trino

```bash
docker exec -it trino trino
```

**Explore the catalog:**
```sql
SHOW CATALOGS;

SHOW SCHEMAS IN local;

SHOW TABLES IN local.analytics_logs;
```

**Query user events:**
```sql
USE local.analytics_logs;

-- Count events by type
SELECT event_type, COUNT(*) as count
FROM user_events
GROUP BY event_type
ORDER BY count DESC;

-- Recent events
SELECT timestamp, user_id, event_type, page_url
FROM user_events
WHERE event_date = CURRENT_DATE
ORDER BY timestamp DESC
LIMIT 10;

-- Events by device type
SELECT device_type, COUNT(*) as count, AVG(duration_ms) as avg_duration
FROM user_events
WHERE event_date >= CURRENT_DATE - INTERVAL '7' DAY
GROUP BY device_type;

-- Show table details
DESCRIBE user_events;

-- Show table partitions
SELECT * FROM "user_events$partitions" LIMIT 10;
```

### 6. Explore Data in MinIO

Open http://localhost:9001 in your browser:
- **Username:** admin
- **Password:** password123

Navigate to the `warehouse` bucket to see:
- **Data files:** Parquet files organized by partition
- **Metadata:** Iceberg metadata files (manifest lists, manifests, snapshots)

## Services & Ports

| Service         | Port  | URL                             | Purpose                          |
|-----------------|-------|---------------------------------|----------------------------------|
| Kafka           | 9092  | localhost:9092                  | Log streaming                    |
| Zookeeper       | 2181  | localhost:2181                  | Kafka coordination               |
| MinIO API       | 9000  | http://localhost:9000           | S3-compatible storage API        |
| MinIO Console   | 9001  | http://localhost:9001           | Web UI for MinIO                 |
| Spark Master UI | 8082  | http://localhost:8082           | Spark cluster monitoring         |
| Trino           | 8081  | http://localhost:8081           | SQL query engine                 |
| Hive Metastore  | 9083  | thrift://localhost:9083         | Iceberg catalog                  |
| PostgreSQL      | 5432  | localhost:5432                  | Metastore database               |

## What's Happening Under the Hood

### 1. Logger Generates JSON
When you call the logger, it:
- Validates required fields (compile-time in Java, runtime in Python)
- Serializes to JSON with the log schema version
- Sends to the configured Kafka topic

### 2. Kafka Buffers Messages
Kafka stores messages in topics with:
- Configurable retention (default: 7 days)
- Replication for reliability
- Partitioning for scalability

### 3. Spark Streams from Kafka
The Spark consumer:
- Reads from Kafka every 10 seconds (micro-batch)
- Parses JSON and validates schema
- Applies transformations (e.g., extracting date from timestamp)
- Writes to Iceberg tables using append operation

### 4. Iceberg Manages Table Format
Iceberg provides:
- **ACID transactions** - Atomic writes, no partial data
- **Schema evolution** - Add/remove columns without rewrites
- **Time travel** - Query historical snapshots
- **Hidden partitioning** - No need to filter by partition column
- **Compaction** - Automatic file optimization

### 5. Data Stored in MinIO
Files are stored in S3:
```
warehouse/
  analytics.logs.user_events/
    metadata/
      v1.metadata.json
      snap-123456.avro
      manifest-list-1.avro
      manifest-1.avro
    data/
      event_date=2024-01-30/
        event_type=click/
          00000-1-abc123.parquet
          00001-1-def456.parquet
```

### 6. Trino Queries Iceberg
Trino uses the Iceberg connector to:
- Read metadata from Hive Metastore
- Scan only relevant Parquet files (predicate pushdown)
- Use partition pruning for fast queries
- Support time travel queries

## Advanced Features

### Schema Evolution

Add a new field to your log config:
```json
{
  "name": "ip_address",
  "type": "string",
  "required": false,
  "description": "Client IP address"
}
```

Regenerate loggers and restart the consumer. Iceberg will automatically handle the schema change!

### Time Travel

Query data as it existed at a specific time:
```sql
SELECT * FROM user_events 
FOR TIMESTAMP AS OF TIMESTAMP '2024-01-30 10:00:00';
```

### Partition Pruning

When you filter by partition columns, Iceberg only scans relevant files:
```sql
-- Only reads data from one partition
SELECT * FROM user_events
WHERE event_date = DATE '2024-01-30'
  AND event_type = 'click';
```

### Compaction

Iceberg automatically manages file sizes, but you can also manually compact:
```sql
CALL local.system.rewrite_data_files(
  table => 'analytics.logs.user_events',
  strategy => 'binpack'
);
```

## Troubleshooting

### Consumer Not Starting

```bash
# Check consumer logs
docker exec spark-master tail -100 /opt/spark-data/consumer.log

# Check if JAR exists
ls -lh spark-consumer/target/structured-log-consumer-1.0.0.jar

# Rebuild if needed
cd spark-consumer && mvn clean package -DskipTests
```

### No Data in Trino

```bash
# Verify logs are in Kafka
docker exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic user-events \
  --from-beginning \
  --max-messages 5

# Check if tables exist in Hive Metastore
docker exec -it trino trino --execute "SHOW TABLES IN local.analytics_logs"

# Verify MinIO has data
docker exec minio mc ls myminio/warehouse/ --recursive
```

### Services Not Healthy

```bash
# Check all container status
docker-compose ps

# View logs for specific service
docker-compose logs kafka
docker-compose logs hive-metastore
docker-compose logs spark-master

# Restart services
docker-compose restart
```

## Stopping the Demo

```bash
# Stop all services
docker-compose down

# Stop and remove volumes (deletes all data)
docker-compose down -v
```

## Next Steps

1. **Create Your Own Log Config**
   - Add a JSON file to `log-configs/`
   - Generate loggers with `make generate`
   - Restart the consumer

2. **Integrate with Your Application**
   - Add the logger library as a dependency
   - Use generated loggers in your code
   - Deploy and monitor

3. **Production Deployment**
   - Scale Kafka brokers for redundancy
   - Add more Spark workers for throughput
   - Use real S3 instead of MinIO
   - Set up proper retention policies
   - Enable monitoring and alerting

## Resources

- [Apache Iceberg Documentation](https://iceberg.apache.org/)
- [Spark Structured Streaming Guide](https://spark.apache.org/docs/latest/structured-streaming-programming-guide.html)
- [Trino Documentation](https://trino.io/docs/current/)
- [Kafka Documentation](https://kafka.apache.org/documentation/)
