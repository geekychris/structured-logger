# Running the Structured Logging Demo

## Quick Start

The structured-logging demo leverages the existing trino_docker infrastructure to avoid port conflicts and service duplication.

### Prerequisites

- Docker and Docker Compose installed and running
- Java 11+ and Maven (for building the Spark consumer)
- Python 3.8+ (for generating loggers and running examples)

### Step 1: Start the Infrastructure

The demo uses the trino_docker project's infrastructure (Kafka, Spark, MinIO, Trino, etc.):

```bash
cd /Users/chris/code/warp_experiments/done/trino_docker
docker-compose up -d

# Wait for services to be healthy (about 1-2 minutes)
docker-compose ps
```

### Step 2: Run the Demo Script

From the structured-logging directory:

```bash
cd /Users/chris/code/warp_experiments/done/structured-logging
./start-demo-with-trino-docker.sh
```

This script will:
1. ✅ Verify that Kafka and Spark are running
2. ✅ Build the Spark consumer JAR if needed
3. ✅ Deploy the JAR to trino_docker's Spark cluster
4. ✅ Copy log configurations to the shared location
5. ✅ Start the Spark streaming consumer

You should see output like:
```
==========================================
✅ Demo Environment Ready!
==========================================

Services (from trino_docker):
  • Kafka:          localhost:9092
  • Zookeeper:      localhost:2181
  • MinIO Console:  http://localhost:9001 (admin/password123)
  • MinIO API:      http://localhost:9000
  • Spark Master:   http://localhost:8082
  • Trino:          http://localhost:8081
  • Hive Metastore: localhost:9083
  • PostgreSQL:     localhost:5432
```

### Step 3: Verify the Consumer is Running

Check the Spark consumer logs:

```bash
docker exec trino_docker-spark-master-1 tail -f /opt/spark-data/consumer.log
```

You should see messages like:
```
Processing log config: UserEvents -> analytics_logs.user_events
✓ Created table: local.analytics_logs.user_events
✓ Table ready: local.analytics_logs.user_events
Started streaming query for UserEvents
```

The consumer has successfully:
- ✅ Loaded all log configurations from `log-configs/`
- ✅ Created Iceberg tables in MinIO
- ✅ Started streaming from Kafka topics

### Step 4: Generate Type-Safe Loggers

Generate Java and Python logger classes from the configurations:

```bash
make generate
```

This creates:
- Java loggers in `java-logger/src/main/java/com/logging/generated/`
- Python loggers in `python-logger/structured_logging/generated/`

### Step 5: Send Test Logs

You can now send logs using the generated loggers. The logs will flow through:
1. Logger → Kafka
2. Kafka → Spark Consumer
3. Spark Consumer → Iceberg Tables (in MinIO)
4. Query via Trino

**Python Example:**
```python
from structured_logging.generated.userevents_logger import UserEventsLogger
from datetime import datetime, date

with UserEventsLogger() as logger:
    logger.log(
        timestamp=datetime.utcnow(),
        event_date=date.today(),
        user_id="demo_user_123",
        session_id="session_456",
        event_type="page_view",
        page_url="/products",
        device_type="desktop",
        duration_ms=1500
    )
```

**Java Example:**
```java
import com.logging.generated.UserEventsLogger;
import java.time.Instant;
import java.time.LocalDate;

try (UserEventsLogger logger = new UserEventsLogger()) {
    logger.log(
        Instant.now(),
        LocalDate.now(),
        "demo_user_123",
        "session_456",
        "page_view",
        "/products",
        null,
        "desktop",
        1500L
    );
}
```

### Step 6: Query Logs with Trino

Connect to Trino:

```bash
docker exec -it trino trino
```

Run queries:

```sql
-- Show available schemas
SHOW SCHEMAS IN local;

-- Show tables in analytics_logs schema
SHOW TABLES IN local.analytics_logs;

-- Query user events
USE local.analytics_logs;

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

-- Table details
DESCRIBE user_events;

-- Show partitions
SELECT * FROM "user_events$partitions" LIMIT 10;
```

### Step 7: Explore Data in MinIO

Open http://localhost:9001 in your browser:
- **Username:** admin
- **Password:** password123

Navigate to the `warehouse` bucket to see:
- Data files (Parquet format) organized by partition
- Iceberg metadata files (manifests, snapshots)

## Architecture

```
┌──────────────────┐
│   Applications   │
│  (Java/Python)   │
└────────┬─────────┘
         │
         ↓
┌──────────────────┐
│  Generated       │
│  Type-Safe       │  ← Generated from JSON configs
│  Loggers         │
└────────┬─────────┘
         │
         ↓
┌──────────────────┐
│      Kafka       │  ← Reliable message streaming
│  localhost:9092  │
└────────┬─────────┘
         │
         ↓
┌──────────────────┐
│ Spark Streaming  │  ← Real-time processing (10s batches)
│   Consumer       │     Running in trino_docker
└────────┬─────────┘
         │
         ↓
┌──────────────────┐
│ Iceberg Tables   │  ← ACID transactions, schema evolution
│   in MinIO/S3    │     Parquet format, partitioned
└────────┬─────────┘
         │
         ↓
┌──────────────────┐
│      Trino       │  ← SQL analytics
│ localhost:8081   │
└──────────────────┘
```

## What's Different from Standalone Mode?

This demo setup differs from the standalone `docker-compose.yml` in this project:

1. **Uses trino_docker infrastructure** - Avoids port conflicts and duplicate services
2. **Shared Spark cluster** - Both projects use the same Spark master/worker
3. **Shared storage** - Same MinIO instance for all data
4. **Shared Hive Metastore** - Single catalog for all tables

Benefits:
- ✅ No port conflicts
- ✅ Resource efficient (one set of services)
- ✅ Can demo both projects simultaneously
- ✅ Realistic multi-tenant setup

## Monitoring

### Check Kafka Topics

```bash
# List all topics
docker exec trino_docker-kafka-1 kafka-topics --bootstrap-server localhost:9092 --list

# Consume from a topic
docker exec trino_docker-kafka-1 kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic user-events \
  --from-beginning \
  --max-messages 5
```

### Check Spark Jobs

Open http://localhost:8082 in your browser to see:
- Running applications
- Completed jobs
- Worker status
- Resource usage

### Check MinIO Storage

```bash
# List warehouse contents
docker exec trino_docker-minio-1 mc ls myminio/warehouse/ --recursive

# Check specific table
docker exec trino_docker-minio-1 mc ls myminio/warehouse/analytics.logs.user_events/
```

## Stopping the Demo

### Stop the Consumer Only

```bash
docker exec trino_docker-spark-master-1 pkill -f StructuredLogConsumer
```

### Stop All Infrastructure

```bash
cd /Users/chris/code/warp_experiments/done/trino_docker
docker-compose down

# To also remove data volumes:
docker-compose down -v
```

## Troubleshooting

### Consumer Not Running

Check logs:
```bash
docker exec trino_docker-spark-master-1 tail -100 /opt/spark-data/consumer.log
```

Common issues:
- JAR not found: Rebuild with `cd spark-consumer && mvn clean package`
- Config errors: Check log-configs/*.json syntax
- Kafka connection: Ensure Kafka is running

### No Data in Trino

Verify the pipeline:

1. **Check Kafka has messages:**
```bash
docker exec trino_docker-kafka-1 kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic user-events \
  --from-beginning --max-messages 1
```

2. **Check consumer is processing:**
```bash
docker exec trino_docker-spark-master-1 tail -f /opt/spark-data/consumer.log
```

3. **Check MinIO has data:**
```bash
docker exec trino_docker-minio-1 mc ls myminio/warehouse/analytics.logs.user_events/data/
```

### Port Conflicts

If you see "port already allocated" errors:
```bash
# Stop conflicting services
cd /Users/chris/code/warp_experiments/done/structured-logging
docker-compose down

# Use trino_docker infrastructure instead
cd /Users/chris/code/warp_experiments/done/trino_docker
docker-compose up -d
```

## Next Steps

1. **Create Custom Log Schemas**
   - Add JSON configs to `log-configs/`
   - Generate loggers with `make generate`
   - Restart consumer to pick up new configs

2. **Build Applications**
   - Use generated loggers in your Java/Python applications
   - Logs automatically flow to Iceberg tables
   - Query with Trino for analytics

3. **Production Deployment**
   - Scale Kafka brokers
   - Add Spark workers
   - Use AWS S3 instead of MinIO
   - Set up monitoring and alerting

## Reference Documentation

- [DEMO.md](DEMO.md) - Detailed demo walkthrough with examples
- [BUILD_AND_RUN.md](BUILD_AND_RUN.md) - Building and configuration guide
- [README.md](README.md) - Project overview and architecture
