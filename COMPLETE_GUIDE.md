# Structured Logging System - Complete Guide

## Table of Contents
1. [Architecture Overview](#architecture-overview)
2. [Components](#components)
3. [Setup Instructions](#setup-instructions)
4. [Running the Demo](#running-the-demo)
5. [Querying Logs](#querying-logs)
6. [Connecting with DataGrip](#connecting-with-datagrip)
7. [Troubleshooting](#troubleshooting)

---

## Architecture Overview

This system provides a production-ready, metadata-driven structured logging pipeline that captures logs from applications, streams them through Kafka, processes them with Spark, stores them in Apache Iceberg tables, and makes them queryable via Trino.

### Data Flow

```
┌──────────────────────┐
│   Java Application   │  Type-safe generated loggers
│   (Your Code)        │  from JSON configurations
└──────────┬───────────┘
           │ Serialize to JSON
           │ Send to Kafka
           ↓
┌──────────────────────┐
│       Kafka          │  Message queue & buffer
│   localhost:9092     │  Topics: user-events, api-metrics
└──────────┬───────────┘
           │ Stream
           │ (10-second micro-batches)
           ↓
┌──────────────────────┐
│  Spark Streaming     │  Real-time processing
│    Consumer          │  Schema validation
│                      │  Transformations
└──────────┬───────────┘
           │ Write (append)
           │ ACID transactions
           ↓
┌──────────────────────┐
│  Apache Iceberg      │  Modern table format
│     Tables           │  - Partitioned by date/type
│  (Stored in MinIO)   │  - Sorted by timestamp
│                      │  - Parquet file format
│                      │  - Snappy compression
└──────────┬───────────┘
           │ Catalog via
           │ Hive Metastore
           ↓
┌──────────────────────┐
│      Trino           │  Distributed SQL engine
│   localhost:8081     │  Query with standard SQL
│   (Catalog: iceberg) │
└──────────────────────┘
```

### Key Features

- **Type-Safe Loggers**: Auto-generated from JSON schemas
- **Real-Time Processing**: 10-second micro-batches
- **ACID Guarantees**: Atomic writes, no partial data
- **Schema Evolution**: Add/change fields without rewrites
- **Time Travel**: Query historical snapshots
- **Efficient Storage**: Parquet with compression
- **Partition Pruning**: Fast queries on date/type filters
- **SQL Analytics**: Standard SQL via Trino

---

## Components

### Infrastructure Stack

| Component | Version | Port | Purpose |
|-----------|---------|------|---------|
| **Kafka** | 7.5.0 | 9092 | Message streaming and buffering |
| **Zookeeper** | 7.5.0 | 2181 | Kafka coordination |
| **Spark Master** | 3.5.0 | 8082 | Stream processing orchestration |
| **Spark Worker** | 3.5.0 | - | Executor for Spark jobs |
| **MinIO** | latest | 9000, 9001 | S3-compatible object storage |
| **PostgreSQL** | 15 | 5432 | Hive Metastore backend |
| **Hive Metastore** | 4.0.0 | 9083 | Iceberg table catalog |
| **Trino** | 435 | 8081 | Distributed SQL query engine |

### Application Components

- **Log Configurations**: JSON schemas defining log structures
- **Code Generators**: Python scripts generating Java/Python loggers
- **Java Logger Library**: Base logger with Kafka producer
- **Python Logger Library**: Base logger with Kafka producer
- **Spark Consumer**: Streaming job (Kafka → Iceberg)

---

## Setup Instructions

### Prerequisites

- Docker & Docker Compose
- Java 11+ and Maven (for building)
- Python 3.8+ (for code generation)
- ~4GB free disk space
- ~4GB available RAM

### Step 1: Start Infrastructure

The demo uses the existing `trino_docker` infrastructure to avoid port conflicts.

```bash
# Navigate to trino_docker project
cd /Users/chris/code/warp_experiments/done/trino_docker

# Start all services
docker-compose up -d

# Wait for services to be healthy (~2 minutes)
docker-compose ps

# Verify services are running
docker ps | grep -E "kafka|spark|trino|minio"
```

Expected output:
```
✔ Container trino_docker-kafka-1           Healthy
✔ Container trino_docker-spark-master-1    Started
✔ Container trino_docker-minio-1           Healthy
✔ Container trino                          Healthy
```

### Step 2: Deploy Spark Consumer

```bash
# Navigate to structured-logging project
cd /Users/chris/code/warp_experiments/done/structured-logging

# Run the setup script
./start-demo-with-trino-docker.sh
```

This script will:
1. ✅ Verify Kafka and Spark are running
2. ✅ Build the Spark consumer JAR (if needed)
3. ✅ Deploy JAR to trino_docker's Spark cluster
4. ✅ Copy log configurations
5. ✅ Start the Spark streaming consumer

### Step 3: Verify Consumer is Running

```bash
# Check Spark consumer logs
docker exec trino_docker-spark-master-1 tail -30 /opt/spark-data/consumer.log
```

You should see:
```
Processing log config: UserEvents -> analytics_logs.user_events
✓ Created table: local.analytics_logs.user_events
Started streaming query for UserEvents

Processing log config: ApiMetrics -> analytics_logs.api_metrics
✓ Created table: local.analytics_logs.api_metrics
Started streaming query for ApiMetrics
```

### Step 4: Generate Logger Code (Optional)

If you want to create new log types:

```bash
# Generate Java and Python loggers from configs
make generate

# Generated files:
# - java-logger/src/main/java/com/logging/generated/
# - python-logger/structured_logging/generated/
```

---

## Running the Demo

### Quick Demo: Send Log Events

```bash
# Send sample log events from Java application
./run-java-demo.sh
```

This sends:
- 2 user events (page view + click)
- 2 API metrics (successful call + error)

Output:
```
✓ Compiled successfully
Sending log events to Kafka...
User events logged successfully
API metrics logged successfully
✅ Log Events Sent!
```

### View Messages in Kafka

```bash
# List topics
docker exec trino_docker-kafka-1 kafka-topics \
  --bootstrap-server localhost:9092 \
  --list

# Consume from user-events topic
docker exec trino_docker-kafka-1 kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic user-events \
  --from-beginning \
  --max-messages 2
```

### Monitor Spark Processing

```bash
# Watch consumer logs in real-time
docker exec trino_docker-spark-master-1 tail -f /opt/spark-data/consumer.log

# Check Spark UI
open http://localhost:8082
```

### Verify Data in MinIO

Web UI:
- URL: http://localhost:9001
- Username: `admin`
- Password: `password123`
- Navigate to `warehouse` bucket → `analytics.logs.user_events/`

CLI:
```bash
# List files in warehouse
docker exec trino_docker-minio-1 mc ls myminio/warehouse/ --recursive

# Check specific table
docker exec trino_docker-minio-1 mc ls myminio/warehouse/analytics.logs.user_events/data/
```

---

## Querying Logs

### Option 1: Command Line (Quick Queries)

**Use the query script:**
```bash
./query-logs.sh
```

Output:
```
Available Tables:
----------------------------------------
"api_metrics"
"user_events"

Record Counts:
----------------------------------------
user_events: 4
api_metrics: 4

Recent User Events (last 5):
----------------------------------------
"user_67890","click","/checkout","mobile","2026-01-30 01:15:21.768Z"
"user_12345","page_view","/products/laptop","desktop","2026-01-30 01:15:21.768Z"

Summary Statistics:
==========================================
User Events by Type:
"page_view","2"
"click","2"
```

**Single query:**
```bash
docker exec trino trino --execute "
  SELECT COUNT(*) as total 
  FROM iceberg.analytics_logs.user_events;
" 2>&1 | grep -v WARNING
```

### Option 2: Interactive SQL Console

```bash
# Connect to Trino CLI
docker exec -it trino trino

# Switch to analytics catalog/schema
USE iceberg.analytics_logs;

# Show tables
SHOW TABLES;

# Query user events
SELECT 
    user_id,
    event_type,
    page_url,
    device_type,
    timestamp
FROM user_events
WHERE event_date = CURRENT_DATE
ORDER BY timestamp DESC
LIMIT 10;

# Query API metrics with aggregation
SELECT 
    service_name,
    COUNT(*) as total_calls,
    AVG(response_time_ms) as avg_response_ms,
    SUM(CASE WHEN status_code >= 400 THEN 1 ELSE 0 END) as errors
FROM api_metrics
WHERE metric_date >= CURRENT_DATE - INTERVAL '7' DAY
GROUP BY service_name
ORDER BY total_calls DESC;

# Exit
quit;
```

### Option 3: SQL Tool (DataGrip, DBeaver, etc.)

See [Connecting with DataGrip](#connecting-with-datagrip) section below.

### Useful Queries

**Events by hour:**
```sql
SELECT 
    date_trunc('hour', timestamp) as hour,
    COUNT(*) as events
FROM iceberg.analytics_logs.user_events
GROUP BY date_trunc('hour', timestamp)
ORDER BY hour DESC;
```

**Failed API calls:**
```sql
SELECT 
    service_name,
    endpoint,
    status_code,
    error_message,
    timestamp
FROM iceberg.analytics_logs.api_metrics
WHERE status_code >= 400
ORDER BY timestamp DESC
LIMIT 20;
```

**User behavior analysis:**
```sql
SELECT 
    user_id,
    COUNT(*) as total_events,
    COUNT(DISTINCT session_id) as sessions,
    COUNT(DISTINCT page_url) as pages_visited
FROM iceberg.analytics_logs.user_events
WHERE event_date = CURRENT_DATE
GROUP BY user_id
ORDER BY total_events DESC;
```

**Performance by endpoint:**
```sql
SELECT 
    endpoint,
    COUNT(*) as calls,
    MIN(response_time_ms) as min_ms,
    AVG(response_time_ms) as avg_ms,
    MAX(response_time_ms) as max_ms,
    PERCENTILE(response_time_ms, 0.95) as p95_ms
FROM iceberg.analytics_logs.api_metrics
WHERE metric_date = CURRENT_DATE
GROUP BY endpoint
ORDER BY avg_ms DESC;
```

**Table metadata:**
```sql
-- Show table structure
DESCRIBE iceberg.analytics_logs.user_events;

-- Show partitions
SELECT * FROM "iceberg.analytics_logs.user_events$partitions" LIMIT 10;

-- Show table history (snapshots)
SELECT * FROM "iceberg.analytics_logs.user_events$history" LIMIT 10;
```

---

## Connecting with DataGrip

### Installation

1. Download DataGrip from JetBrains: https://www.jetbrains.com/datagrip/
2. Install and launch DataGrip

### Configure Trino Connection

#### Step 1: Create New Data Source

1. Click **Database** → **New** → **Data Source** → **Trino**
2. If Trino is not in the list, select **Presto** (compatible)

#### Step 2: Connection Settings

Fill in the following:

```
Name: Structured Logging (Trino)
Host: localhost
Port: 8081
User: admin
Password: (leave empty)
Database: iceberg
```

**IMPORTANT**: The database field must be `iceberg` (the catalog name), NOT `analytics_logs`.

#### Step 3: Advanced Settings (Optional)

In the **Advanced** tab:
```
Default schema: analytics_logs
```

#### Step 4: Driver Settings

1. Click **Download missing driver files** if prompted
2. Click **Test Connection**

You should see: ✓ **Connection successful**

#### Step 5: Apply and Connect

1. Click **OK**
2. Expand the connection in the Database panel

You should see:
```
Structured Logging (Trino)
  └── iceberg
       ├── analytics_logs
       │    ├── api_metrics
       │    ├── test_schema
       │    └── user_events
       ├── default
       └── information_schema
```

### Querying in DataGrip

#### Method 1: Right-click Table
1. Right-click on `user_events`
2. Select **SQL Scripts** → **SELECT *...**
3. Run the query (Ctrl+Enter / Cmd+Enter)

#### Method 2: New Console
1. Right-click connection → **New** → **Query Console**
2. Write your queries:

```sql
-- Set default schema
USE iceberg.analytics_logs;

-- Query tables
SELECT * FROM user_events LIMIT 10;
SELECT * FROM api_metrics WHERE status_code >= 400;
```

#### Method 3: Fully Qualified Names

Always works regardless of current schema:
```sql
SELECT * FROM iceberg.analytics_logs.user_events;
SELECT * FROM iceberg.analytics_logs.api_metrics;
```

### DataGrip Tips

**View current catalog/schema:**
```sql
SELECT current_catalog, current_schema;
```

**Quick table preview:**
- Right-click table → **Jump to Console** → Opens with SELECT query

**Export results:**
- Run query → Click export icon → Choose format (CSV, JSON, etc.)

**Schema comparison:**
- Right-click table → **Compare With** → Compare schemas

**Table diagram:**
- Right-click schema → **Diagrams** → **Show Visualization**

### Troubleshooting DataGrip Connection

**Issue: "No tables visible"**
- Solution: Make sure **Database** field is set to `iceberg`, not empty or `analytics_logs`
- Verify: Run `SELECT current_catalog;` → should return `"iceberg"`

**Issue: "Connection refused"**
- Check Trino is running: `docker ps | grep trino`
- Check Trino is healthy: Look for `(healthy)` status
- Wait 30-60 seconds after starting, Trino takes time to initialize

**Issue: "User authentication failed"**
- Leave password field **empty**
- Use `admin` as username (or any value, Trino doesn't authenticate by default)

**Issue: Slow queries**
- Trino needs time to warm up on first query (~30 seconds)
- Subsequent queries should be faster

---

## Troubleshooting

### Consumer Not Processing Messages

**Check if consumer is running:**
```bash
docker exec trino_docker-spark-master-1 ps aux | grep StructuredLogConsumer
```

**View consumer logs:**
```bash
docker exec trino_docker-spark-master-1 tail -100 /opt/spark-data/consumer.log
```

**Restart consumer:**
```bash
./start-demo-with-trino-docker.sh
```

### No Data in Tables

**Verify messages in Kafka:**
```bash
docker exec trino_docker-kafka-1 kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic user-events \
  --from-beginning \
  --max-messages 1
```

**Check Spark is processing:**
```bash
# Should show batch processing every 10 seconds
docker exec trino_docker-spark-master-1 tail -f /opt/spark-data/consumer.log | grep "Processing batch"
```

**Verify data in MinIO:**
```bash
docker exec trino_docker-minio-1 mc ls myminio/warehouse/analytics.logs.user_events/data/
```

### Trino Not Responding

**Check Trino status:**
```bash
docker ps | grep trino
# Should show (healthy) status
```

**If unhealthy, restart:**
```bash
docker restart trino
sleep 30  # Wait for initialization
docker ps | grep trino
```

**View Trino logs:**
```bash
docker logs trino --tail 50
```

### Services Not Starting

**Check all services:**
```bash
cd /Users/chris/code/warp_experiments/done/trino_docker
docker-compose ps
```

**Restart all services:**
```bash
docker-compose down
docker-compose up -d
```

**Check for port conflicts:**
```bash
lsof -i :9092  # Kafka
lsof -i :8081  # Trino
lsof -i :5432  # PostgreSQL
```

### Build Errors

**Rebuild Spark consumer:**
```bash
cd spark-consumer
mvn clean package -DskipTests
```

**Rebuild Java logger:**
```bash
cd java-logger
mvn clean install
```

**Regenerate loggers:**
```bash
make generate
```

---

## Advanced Topics

### Creating Custom Log Types

1. **Create a JSON configuration:**

```bash
cat > log-configs/payment_events.json << 'EOF'
{
  "name": "PaymentEvents",
  "version": "1.0.0",
  "description": "Payment transaction events",
  "kafka": {
    "topic": "payment-events",
    "partitions": 6,
    "replication_factor": 1
  },
  "warehouse": {
    "table_name": "analytics.logs.payment_events",
    "partition_by": ["event_date", "payment_status"],
    "sort_by": ["timestamp", "transaction_id"],
    "retention_days": 365
  },
  "fields": [
    {
      "name": "timestamp",
      "type": "timestamp",
      "required": true,
      "description": "Event timestamp"
    },
    {
      "name": "event_date",
      "type": "date",
      "required": true,
      "description": "Event date for partitioning"
    },
    {
      "name": "transaction_id",
      "type": "string",
      "required": true,
      "description": "Unique transaction identifier"
    },
    {
      "name": "user_id",
      "type": "string",
      "required": true,
      "description": "User identifier"
    },
    {
      "name": "amount",
      "type": "double",
      "required": true,
      "description": "Payment amount"
    },
    {
      "name": "currency",
      "type": "string",
      "required": true,
      "description": "Currency code (USD, EUR, etc.)"
    },
    {
      "name": "payment_status",
      "type": "string",
      "required": true,
      "description": "Status: success, failed, pending"
    },
    {
      "name": "payment_method",
      "type": "string",
      "required": false,
      "description": "Method: card, paypal, etc."
    }
  ]
}
EOF
```

2. **Generate loggers:**
```bash
make generate
```

3. **Restart consumer to pick up new config:**
```bash
./start-demo-with-trino-docker.sh
```

4. **Use the generated logger:**
```java
import com.logging.generated.PaymentEventsLogger;
import java.time.Instant;
import java.time.LocalDate;

try (PaymentEventsLogger logger = new PaymentEventsLogger()) {
    logger.log(
        Instant.now(),
        LocalDate.now(),
        "txn_123456",
        "user_789",
        99.99,
        "USD",
        "success",
        "card"
    );
}
```

### Schema Evolution

Add a new field to an existing log config and regenerate. Iceberg will handle the schema change automatically without rewriting data.

### Production Deployment

- Scale Kafka brokers for redundancy
- Add more Spark workers for throughput
- Use AWS S3 instead of MinIO
- Set up proper retention policies
- Enable monitoring and alerting
- Secure with authentication/encryption

---

## Quick Reference

### Key URLs
- **MinIO Console**: http://localhost:9001 (admin/password123)
- **Spark UI**: http://localhost:8082
- **Trino**: http://localhost:8081

### Key Commands
```bash
# Start infrastructure
cd /Users/chris/code/warp_experiments/done/trino_docker && docker-compose up -d

# Start consumer
cd /Users/chris/code/warp_experiments/done/structured-logging
./start-demo-with-trino-docker.sh

# Send test logs
./run-java-demo.sh

# Query logs
./query-logs.sh

# View consumer logs
docker exec trino_docker-spark-master-1 tail -f /opt/spark-data/consumer.log
```

### Key Locations
- **Log configs**: `log-configs/*.json`
- **Generated Java loggers**: `java-logger/src/main/java/com/logging/generated/`
- **Generated Python loggers**: `python-logger/structured_logging/generated/`
- **Spark consumer JAR**: `spark-consumer/target/structured-log-consumer-1.0.0.jar`
- **Consumer logs**: `/opt/spark-data/consumer.log` (in Spark container)

---

## Support

For issues or questions:
1. Check the [Troubleshooting](#troubleshooting) section
2. View component logs using `docker logs <container_name>`
3. Check service health with `docker ps`
4. Review documentation:
   - [DEMO.md](DEMO.md) - Detailed demo walkthrough
   - [BUILD_AND_RUN.md](BUILD_AND_RUN.md) - Build instructions
   - [RUNNING_THE_DEMO.md](RUNNING_THE_DEMO.md) - Operational guide
