# Structured Logging System

A metadata-driven structured logging framework that generates type-safe loggers for Java and Python, publishes to Kafka, and automatically ingests logs into **Apache Iceberg** data warehouse tables using Spark Streaming.

## Key Features

- **Apache Iceberg Tables**: Modern table format with ACID transactions, schema evolution, and time travel
- **S3/MinIO Storage**: Scalable object storage for Parquet data files and Iceberg metadata
- **Type-Safe Loggers**: Auto-generated Java and Python loggers from JSON configs
- **Kafka Streaming**: Reliable, high-throughput log ingestion
- **Spark Processing**: Real-time stream processing with 10-second micro-batches
- **Trino Queries**: SQL access to Iceberg tables for analytics and monitoring

## Architecture

```
Application (Java/Python)
    ↓
Generated Logger (type-safe)
    ↓
Base Logger → Kafka Topic
    ↓
Spark Streaming Consumer
    ↓
Apache Iceberg Tables (Parquet files in S3/MinIO)
    ↓
Trino/Presto (SQL Queries)
```

## Technology Stack

- **Kafka**: Message streaming and buffering
- **Spark 3.5**: Stream processing engine
- **Apache Iceberg 1.4**: Modern table format with ACID guarantees
- **MinIO/S3**: Object storage (S3-compatible)
- **Parquet**: Columnar file format with compression
- **Trino**: Distributed SQL query engine
- **Hive Metastore**: Catalog for table metadata

## Features

- **Metadata-Driven Configuration**: Define log schemas once in JSON, use everywhere
- **Type-Safe Code Generation**: Automatically generate Java and Python loggers from configs
- **Kafka Integration**: Efficient, reliable log streaming
- **Iceberg Tables**: Columnar storage with partitioning, sorting, and retention
- **Easy Extensibility**: Add new log types by creating a config file

## Directory Structure

```
structured-logging/
├── config-schema/          # JSON schema for log configs
├── generators/             # Code generators for Java and Python
├── java-logger/            # Base Java logger and generated code
├── python-logger/          # Base Python logger and generated code
├── spark-consumer/         # Spark job for Kafka → Iceberg
└── examples/               # Example log configurations
```

## Quick Start

### 0. Reset and Rebuild (if needed)

**Interactive mode** (prompts for options):
```bash
./reset-and-rebuild.sh
```

**Automatic mode** (no prompts):
```bash
# Basic reset (preserves Kafka data)
./reset-and-rebuild-auto.sh

# Full reset with Kafka cleanup  
./reset-and-rebuild-auto.sh --clear-kafka

# Full reset with code regeneration
./reset-and-rebuild-auto.sh --clear-kafka --regenerate
```

**What it does:**
1. Drops all Iceberg tables
2. Stops Spark consumer
3. Optionally clears Kafka topics
4. Optionally regenerates logger code
5. Rebuilds Java logger
6. Updates and restarts consumer
7. Sends test data
8. Verifies envelope format

**Verify everything works:**
```bash
./verify_envelope.sh
```

### 1. Create a Log Configuration

Create a JSON file defining your log schema (see `examples/user_events.json`):

```json
{
  "name": "UserEvents",
  "version": "1.0.0",
  "description": "Tracks user interaction events",
  "kafka": {
    "topic": "user-events",
    "partitions": 6,
    "replication_factor": 3
  },
  "warehouse": {
    "table_name": "analytics.logs.user_events",
    "partition_by": ["event_date", "event_type"],
    "sort_by": ["timestamp", "user_id"]
  },
  "fields": [
    {
      "name": "timestamp",
      "type": "timestamp",
      "required": true,
      "description": "Event timestamp"
    },
    {
      "name": "user_id",
      "type": "string",
      "required": true,
      "description": "User identifier"
    }
    // ... more fields
  ]
}
```

### 2. Generate Loggers

```bash
cd generators
python3 generate_loggers.py ../examples/user_events.json
```

This generates:
- Java: `java-logger/src/main/java/com/logging/generated/UserEventsLogger.java`
- Python: `python-logger/structured_logging/generated/userevents_logger.py`

### 3. Use the Logger (Java)

```java
import com.logging.generated.UserEventsLogger;
import java.time.Instant;
import java.time.LocalDate;

try (UserEventsLogger logger = new UserEventsLogger()) {
    logger.log(
        Instant.now(),           // timestamp
        LocalDate.now(),         // event_date
        "user123",              // user_id
        "session456",           // session_id
        "click",                // event_type
        "/products/widget",     // page_url
        null,                   // properties
        "mobile",               // device_type
        1500L                   // duration_ms
    );
}
```

Or use the builder pattern:

```java
UserEventsLogger.builder()
    .timestamp(Instant.now())
    .eventDate(LocalDate.now())
    .userId("user123")
    .sessionId("session456")
    .eventType("view")
    .pageUrl("/home")
    .deviceType("desktop")
    .build();
```

### 4. Use the Logger (Python)

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

### 5. Run the Spark Consumer

Build and run with S3/MinIO storage:

```bash
# Build the consumer
cd spark-consumer
mvn clean package -DskipTests
cp target/structured-log-consumer-1.0.0.jar ../../../spark-apps-java/

# Start the consumer with S3/MinIO backend
cd ..
./start-consumer-s3.sh
```

The Spark consumer will:
- Read log configs from the config directory
- Subscribe to corresponding Kafka topics
- Create Apache Iceberg tables if they don't exist
- Stream data from Kafka to Iceberg tables (stored in S3/MinIO)
- Apply partitioning and sorting as configured
- Write Parquet data files to S3

## Complete Documentation

**Quick Links**:
- 📝 [Adding New Loggers](ADDING_NEW_LOGGERS.md) - Start here for creating new log types!
- 📊 [Configuration Flow Diagram](docs/CONFIG_FLOW_DIAGRAM.md) - Visual guide
- 📚 [Build & Run Guide](BUILD_AND_RUN.md) - Building and deployment
- ⚡ [Quick Reference](QUICK_REFERENCE.md) - Common operations

### Detailed Guides

### 📝 [ADDING_NEW_LOGGERS.md](ADDING_NEW_LOGGERS.md) ⭐ NEW!
**Step-by-step guide for creating new log types**:
- Understanding the configuration flow
- Creating log configurations
- Generating type-safe logger code
- How Spark consumer auto-discovers configs (Docker volume mounts)
- Testing and verification
- Troubleshooting common issues

### 📚 [BUILD_AND_RUN.md](BUILD_AND_RUN.md)
Comprehensive guide covering:
- Building all components (Spark consumer, logger libraries)
- Creating and generating log configurations
- Running the Spark consumer with S3/MinIO
- Testing the system end-to-end
- **Querying Iceberg tables with Trino** (SQL examples)
- Monitoring and troubleshooting

### 🚀 [S3_DEPLOYMENT.md](S3_DEPLOYMENT.md)
S3/MinIO deployment guide:
- Why use S3 storage for Iceberg
- Configuration layers (S3A, Iceberg S3FileIO, AWS SDK)
- Required dependencies
- Production considerations
- Performance tuning

### ⚡ [QUICK_REFERENCE.md](QUICK_REFERENCE.md)
Quick reference for common tasks:
- System status checks
- Starting/stopping consumer
- Creating new log configs
- Testing and verification
- Trino query examples
- Troubleshooting tips

### 🏗️ [STANDALONE_SETUP.md](STANDALONE_SETUP.md)
**NEW**: Standalone deployment guide:
- Running as independent project (no parent dependencies)
- Self-contained Docker Compose setup
- Moving the project to any location
- Complete service stack included
- See also: [MIGRATION_NOTES.md](MIGRATION_NOTES.md)

### 🔄 [SCHEMA_EVOLUTION.md](SCHEMA_EVOLUTION.md)
**Schema evolution and versioning**:
- Automatic schema change detection
- Supported vs. unsupported changes
- Safe migration strategies
- Schema versioning with Iceberg
- Time travel and rollback
- Troubleshooting schema issues

### 💾 [STORAGE_CONFIGURATION.md](STORAGE_CONFIGURATION.md)
**Data storage and persistence**:
- Host directory vs Docker volumes
- MinIO data on host filesystem
- Backup and restore procedures
- Disk space management
- Data portability

## Iceberg Features

This system uses **Apache Iceberg** for table management, providing:

- **ACID Transactions**: Atomic commits for streaming writes
- **Schema Evolution**: Add/modify columns without rewriting data (see [SCHEMA_EVOLUTION.md](SCHEMA_EVOLUTION.md))
- **Time Travel**: Query historical snapshots of data
- **Partition Evolution**: Change partitioning without rewriting
- **Hidden Partitioning**: Partition transparently (no partition columns in queries)
- **Snapshot Isolation**: Consistent reads while writes occur
- **Metadata Layers**: Efficient metadata operations

### Example Trino Queries

```sql
-- Query current data
SELECT * FROM iceberg.analytics.logs.user_events LIMIT 10;

-- Time travel to specific snapshot
SELECT COUNT(*) FROM iceberg.analytics.logs.user_events 
FOR VERSION AS OF 7864623715751563766;

-- View table snapshots
SELECT * FROM iceberg.analytics.logs.user_events.snapshots;

-- Check partitions and data files
SELECT * FROM iceberg.analytics.logs.user_events.files;
```

See [BUILD_AND_RUN.md](BUILD_AND_RUN.md) for many more query examples!

## Configuration Reference

### Log Config Schema

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Unique name for the log category (PascalCase) |
| `version` | string | Schema version (semver format) |
| `description` | string | Human-readable description |
| `kafka.topic` | string | Kafka topic name |
| `kafka.partitions` | int | Number of Kafka partitions |
| `kafka.replication_factor` | int | Replication factor |
| `kafka.retention_ms` | long | Retention period in milliseconds |
| `warehouse.table_name` | string | Fully qualified table name |
| `warehouse.partition_by` | array | Fields to partition by |
| `warehouse.sort_by` | array | Fields to sort by |
| `warehouse.retention_days` | int | Data retention in days |
| `fields` | array | Log field definitions |

### Field Types

Supported field types:
- `string`, `int`, `long`, `float`, `double`, `boolean`
- `timestamp`, `date`
- `array<string>`, `array<int>`, `array<long>`
- `map<string,string>`

## Building

### Java Logger

```bash
cd java-logger
mvn clean package
```

### Python Logger

```bash
cd python-logger
pip install -r requirements.txt
```

### Spark Consumer

```bash
cd spark-consumer
mvn clean package
```

## Environment Variables

- `KAFKA_BOOTSTRAP_SERVERS`: Kafka bootstrap servers (default: `localhost:9092`)

## Adding a New Log Type

**Quick Steps:**

1. **Create config**: Add `log-configs/my_new_log.json` with your schema
2. **Generate loggers**: `python generators/generate_loggers.py log-configs/my_new_log.json`
3. **Restart consumer**: `./start-consumer.sh` (auto-discovers new config via Docker volume mount)
4. **Use logger**: Import and use the generated type-safe logger in your app
5. **Query data**: Use Trino to query your Iceberg table

**📖 For detailed instructions with examples and troubleshooting, see: [ADDING_NEW_LOGGERS.md](ADDING_NEW_LOGGERS.md)**

The Spark consumer automatically discovers new configs because `log-configs/` is mounted into the container via Docker volumes - no manual copying needed!

## Best Practices

1. **Versioning**: Update the `version` field when changing schemas
2. **Partitioning**: Choose partition keys that evenly distribute data (date + high cardinality field)
3. **Sorting**: Sort by commonly filtered fields for query performance
4. **Required Fields**: Mark fields as required only if they're truly essential
5. **Field Names**: Use snake_case for field names
6. **Kafka Topics**: Use descriptive, kebab-case topic names

## Architecture Details

### Code Generation

The generator reads log configs and produces:
- **Java**: Type-safe logger classes with builder pattern support
- **Python**: Type-hinted logger classes with optional fields
- Both inherit from base loggers that handle Kafka serialization

### Kafka Publishing

- JSON serialization with Jackson (Java) / standard library (Python)
- Snappy compression
- Async publishing with error callbacks
- Configurable batching and linger time

### Spark Processing

- Structured Streaming for continuous processing
- Automatic schema inference from configs
- Iceberg table creation with partitioning
- Checkpoint management for exactly-once semantics
- Multiple configs can be processed by one Spark job

### Data Warehouse

- Iceberg format for ACID compliance and schema evolution
- Parquet files with Snappy compression
- Automatic partitioning for query performance
- Metadata-driven table properties

## Monitoring

Monitor the system through:
1. Kafka consumer lag metrics
2. Spark streaming UI (`http://localhost:4040`)
3. Application logs (SLF4J/Python logging)
4. Data warehouse query performance

## Troubleshooting

### Logger not publishing
- Check `KAFKA_BOOTSTRAP_SERVERS` environment variable
- Verify Kafka is running and accessible
- Check application logs for connection errors

### Spark consumer fails
- Verify Kafka topic exists
- Check Iceberg table permissions
- Review Spark logs for detailed errors
- Ensure checkpoint directory is writable

### Schema evolution
- Update the `version` field in config
- Regenerate loggers
- Iceberg handles compatible schema changes automatically

## License

MIT
