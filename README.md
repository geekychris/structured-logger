# Structured Logging System

A metadata-driven structured logging framework that generates type-safe loggers for Java and Python, publishes to Kafka, and automatically ingests logs into **Apache Iceberg** data warehouse tables using Spark Streaming.

## Key Features

- **Apache Iceberg Tables**: Modern table format with ACID transactions, schema evolution, and time travel
- **S3/MinIO Storage**: Scalable object storage for Parquet data files and Iceberg metadata
- **Type-Safe Loggers**: Auto-generated Java and Python loggers from JSON configs
- **Kafka Streaming**: Reliable, high-throughput log ingestion
- **Spark Processing**: Real-time stream processing with 10-second micro-batches
- **Trino Queries**: SQL access to Iceberg tables for analytics and monitoring

## Flink Table API tutorial

A self-contained tutorial in [`flink-tutorial/`](flink-tutorial/) brings up an Apache Flink 1.18 session cluster (Docker Compose **or** Kubernetes), exposes the Web UI on coordinated ports, and ships JDWP on both the JobManager and TaskManager so you can attach IntelliJ or VSCode to step through live operator code. Includes two ready-to-submit Table API examples and a narrated walkthrough video. See **[flink-tutorial/README.md](flink-tutorial/README.md)** and **[flink-tutorial/demo/flink-tutorial-walkthrough.mp4](flink-tutorial/demo/flink-tutorial-walkthrough.mp4)**.

## Transport Benchmark

A standalone harness in [`bench/`](bench/) compares five ways to deliver records into a warehouse-ready landing zone — Kafka+JSON, Kafka+Avro+Schema-Registry, sidecar→S3+Avro, sidecar→S3+Parquet+Zstd, and WarpStream — on latency, CPU, network, storage, and cost per million rows. Headline finding: Kafka delivers ~7 ms p50 for ~$0.11/M rows; sidecar→S3+Parquet is **15× cheaper** but ~12 s p50; WarpStream sits between at ~400 ms p50. Full report with Mermaid diagrams, per-column Parquet compression analysis, and reproducible commands: **[bench/REPORT.md](bench/REPORT.md)**.

## Approach availability

All five approaches the bench measured are now wired into the production loggers AND end-to-end into Iceberg/Trino. Pick one per `log-configs/*.json`:

| Approach | Configure with | Java | Python | Lands in Iceberg via | Trino-queryable |
|---|---|:-:|:-:|---|:-:|
| **A** Kafka + JSON+Snappy | `transport.sinks: ["kafka"]` (default) | ✅ | ✅ | `kafka-to-iceberg-consumer.py` | ✅ |
| **B** Kafka + Avro+SR | `transport.sinks: ["kafka"], encoding: "avro", schema_registry_url: ...` | ✅ | ✅ | `kafka-to-iceberg-consumer.py` (auto-detects encoding via `from_avro`) | ✅ |
| **C** Sidecar → S3 Avro | `transport.sinks: ["s3"], s3.encoding: "avro"` (in-process) **or** `transport.sinks: ["file"]` + run a separate sidecar process | ✅ | ✅ | `s3-to-iceberg-consumer.py` (`readStream.format("avro")`) | ✅ |
| **D** Sidecar → S3 Parquet+Zstd | same as C with `s3.encoding: "parquet"` | ✅ | ✅ | `s3-to-iceberg-consumer.py` (`readStream.format("parquet")`) | ✅ |
| **E** WarpStream | `transport.sinks: ["kafka"]`, point `KAFKA_BOOTSTRAP_SERVERS` at WarpStream agent | ✅ (zero code change — Kafka API compatible) | ✅ | `kafka-to-iceberg-consumer.py` | ✅ |

### Bringing up the lakehouse stack

This project depends on the [`spark_minio_trino`](https://github.com/geekychris/spark_minio_trino) lakehouse for Spark, Hive Metastore, MinIO, Kafka, and Trino. Clone it next to this project:

```bash
git clone https://github.com/geekychris/spark_minio_trino.git ../spark_minio_trino
```

The default ports it uses (5432, 8081, 9092, ...) collide with other infrastructure on a typical dev machine running multiple stacks. Use the included override file to remap them to a coordinated, devportal-allocated range:

```bash
docker compose -f ../spark_minio_trino/docker-compose.yml -f lakehouse-override.yml up -d
```

Host port map (anchored on devportal-allocated `spark-minio-trino:http=18013`):

| Service | Host port | Container port | Used for |
|---|---|---|---|
| MinIO API | 18000 | 9000 | S3 client → MinIO |
| MinIO console | 18001 | 9001 | Browser UI (`http://127.0.0.1:18001`, `admin`/`password123`) |
| **Trino** | **18013** | 8080 | SQL client; `http://127.0.0.1:18013/ui/` |
| Postgres | 15432 | 5432 | Hive metastore backend |
| Hive Metastore | 18083 | 9083 | Iceberg catalog |
| Spark master UI | 18082 | 8080 | `http://127.0.0.1:18082` |
| Spark master | 18077 | 7077 | Spark cluster coord |
| Schema Registry | 18081 | 8081 | Used by Approach B |
| Kafka (host clients) | 18092 | 9092 | Producers from your laptop |
| Zookeeper | 18181 | 2181 | Kafka coordination |

Container-to-container traffic on `lakehouse-network` is unchanged (`kafka:29092`, `hive-metastore:9083`, `minio:9000`, `schema-registry:8081`), so the consumer code never sees the remapped ports — only host-side scripts do.

### Running the consumers

```bash
# Kafka source only (approaches A, B, E) — the historical default
./start-consumer.sh kafka

# S3 file source only (approaches C, D)
./start-consumer.sh s3

# Both consumers in parallel
./start-consumer.sh both
```

Both consumers iterate `log-configs/*.json` and start one streaming query per matching log type:
- The **Kafka consumer** picks up configs whose `transport.sinks` includes `"kafka"` (or no `transport` block at all = default to kafka). Skips s3-only configs cleanly.
- The **S3 consumer** picks up configs whose `transport.sinks` includes `"s3"`. Skips kafka-only configs.

Both write to the same `iceberg.analytics_logs.*` namespace, so Trino can't tell which transport delivered the rows.

The Spark `--packages` list bundles `org.apache.spark:spark-avro_2.12:3.5.0` — required by the Avro Kafka decoder (B) and the Avro file reader (C).

### Per-approach config recipes

Add a `transport` block to your `log-configs/<name>.json`. The `STRUCTURED_LOG_SINKS` env var overrides `transport.sinks` at runtime, so you can swap transports per environment without rebuilding.

**A — Kafka + JSON+Snappy** (the default; can be omitted entirely):
```json
{
  "transport": {
    "sinks": ["kafka"],
    "encoding": "json",
    "kafka": {"compression": "snappy"}
  }
}
```

**B — Kafka + Avro + Schema Registry** (~half the wire bytes vs JSON):
```json
{
  "transport": {
    "sinks": ["kafka"],
    "encoding": "avro",
    "schema_registry_url": "http://schema-registry:8081"
  }
}
```
The Avro schema for the envelope is *derived from your `fields` array* at startup and registered with Schema Registry. The Spark consumer will auto-detect `encoding: "avro"` and decode Confluent-wire-format Avro.

**C — Sidecar pattern → S3 (Avro)**:

Two deployment styles:

*In-process sidecar* (the application contains the S3-uploader thread):
```json
{
  "transport": {
    "sinks": ["s3"],
    "s3": {
      "bucket": "my-logs",
      "endpoint": "http://minio:9000",
      "path_style": true,
      "encoding": "avro",
      "rotate_seconds": 30,
      "rotate_bytes": 16777216,
      "max_records": 50000,
      "key_prefix": "user_events"
    }
  }
}
```

*External sidecar process* (app writes NDJSON to disk, separate process tails and ships):
```json
{ "transport": { "sinks": ["file"], "file": { "dir": "/var/log/app" } } }
```
On the same host (or pod), run a sidecar that points at `/var/log/app` and ships somewhere — see Java's `com.logging.sidecar.Sidecar` (already wired up) or use the standalone Python sidecar in `bench/driver/sidecar.py` as a starting point.

**D — Sidecar / in-process → S3 (Parquet+Zstd)** — same as C with `"encoding": "parquet"`. Beats Avro on at-rest compression by ~30% (see the per-column analysis in [bench/REPORT.md](bench/REPORT.md#why-parquetzstd-compresses-so-well--column-by-column)).

**E — WarpStream** — no config change, just point at a WarpStream agent:
```bash
export KAFKA_BOOTSTRAP_SERVERS=warpstream-agent.local:9092
```
Use either approach A or B's config; WarpStream speaks the Kafka protocol natively. Trade ~400 ms latency for no broker hours.

### End-to-end verification (Trino query)

A driver script `test_e2e.py` exercises every approach against the running lakehouse and prints the verification queries. Sample run output (after 5 records pushed via each approach):

```
>>> Approach A: Kafka + JSON+Snappy
  pushed 5 records via kafka_json[user-events]

>>> Approach B: Kafka + Avro+SR
  pushed 5 records via kafka_avro[user-events-avro sr_id=1]

>>> Approach C: Sidecar→S3 Avro (in-process)
  pushed 5 records via s3[lakehouse enc=avro]

>>> Approach D: Sidecar→S3 Parquet+Zstd (in-process)
  pushed 5 records via s3[lakehouse enc=parquet]
```

After ~30s of micro-batch settle time, query each landed table from Trino:

```bash
docker exec trino trino --server localhost:8080 --execute "
  SELECT 'A: kafka+json'         AS approach, COUNT(*) FROM iceberg.analytics_logs.user_events
  UNION ALL SELECT 'B: kafka+avro+SR',         COUNT(*) FROM iceberg.analytics_logs.user_events_kafka_avro
  UNION ALL SELECT 'C: sidecar→s3 avro',       COUNT(*) FROM iceberg.analytics_logs.user_events_s3_avro
  UNION ALL SELECT 'D: sidecar→s3 parquet',    COUNT(*) FROM iceberg.analytics_logs.user_events_s3_parquet
"
```

Trino UI at `http://127.0.0.1:18013/ui/` (no login required for default Trino).

**Sample log configs** for each approach are in `log-configs/`:
- `user_events.json` — Approach A (default kafka+json)
- `user_events_kafka_avro.json` — Approach B (kafka+avro+SR)
- `user_events_s3_avro.json` — Approach C (in-process s3 sink, Avro)
- `user_events_s3_parquet.json` — Approach D (in-process s3 sink, Parquet+Zstd)

**Verifying without the lakehouse stack**: `spark-consumer/test_schema_contract.py` brings up just MinIO and confirms the bytes the sidecar writes are exactly what the s3 consumer expects to read. Useful when iterating on schemas:

```bash
cd bench && docker compose up -d minio minio-init
cd .. && python3 spark-consumer/test_schema_contract.py
```

### Common gotchas

If you're bringing this up fresh, these are the things that bit me during integration:

- **Kafka producer hangs on metadata refresh** → the override file's `KAFKA_ADVERTISED_LISTENERS` must use `127.0.0.1` not `localhost` (macOS Docker port-mapping is IPv4-only; kafka-python's metadata refresh resolves `localhost` to `::1` and silently times out).
- **Kafka `UnrecognizedBrokerVersion`** → kafka-python's auto-version-detect is fragile against Confluent 7.x; the project's sinks pin `api_version=(2,5,0)`.
- **Spark consumer can't find spark-avro classes** → the `--packages` list in `start-consumer.sh` includes `org.apache.spark:spark-avro_2.12:3.5.0`. If you forked the script, keep that.
- **Avro schema fetched as 404 at consumer startup** → the consumer fetches the schema at query setup, not at message-consumption time. Run a producer at least once before starting the consumer, or restart the consumer after first records flow.
- **`Field _ingestion_timestamp not found in source schema`** → the s3 consumer used to add this column; removed for parity with the kafka consumer. If you want it, add to BOTH consumers AND to the table DDL in `_create_iceberg_table_if_not_exists`.

### Composing sinks (fan-out)

Multiple sinks compose left-to-right via `CompositeSink`:
```json
{ "transport": { "sinks": ["kafka", "file"] } }
```
The application publishes once; each record goes to both Kafka and the local NDJSON file. Useful for migration ("write to both during cutover, switch downstream consumers, then drop one") and for cheap local debugging (`["kafka", "slf4j"]`).

### Env-var overrides (operations layer)

Operators can override per-deployment without rebuilding:
```bash
STRUCTURED_LOG_SINKS=KAFKA,FILE        # override transport.sinks
KAFKA_BOOTSTRAP_SERVERS=broker:9092
SCHEMA_REGISTRY_URL=http://sr:8081
STRUCTURED_LOG_FILE_DIR=/var/log/app
STRUCTURED_LOG_S3_BUCKET=my-logs
STRUCTURED_LOG_S3_ENDPOINT=http://minio:9000
STRUCTURED_LOG_S3_ENCODING=parquet
```
Java and Python both honor the same env-var contract.

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

### 0. Automated Build and Setup (Recommended)

For a comprehensive setup that checks dependencies, builds all components, and offers to run tests:

```bash
./build-and-setup.sh
```

**What it does:**
1. ✓ Checks system dependencies (Docker, Java, Maven, Python)
2. ✓ Verifies Docker services are running (Kafka, Spark, MinIO, Trino)
3. ✓ Sets up Python virtual environment
4. ✓ Builds Java logger library
5. ✓ Builds Spark consumer
6. ✓ Optionally generates loggers from configs
7. ✓ Offers to run test examples

If Docker services are not running, the script will:
- Show you which services are missing
- Provide instructions on how to start them
- Allow you to continue or exit

This is the easiest way to get started!

### Alternative: Reset and Rebuild (if needed)

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
# One-time setup: Create venv and install dependencies
bash setup_python_env.sh

# Or manually:
python3 -m venv python-logger/venv
source python-logger/venv/bin/activate
pip install -r python-logger/requirements.txt
```

**Note**: Python example scripts are configured to use the virtual environment automatically.

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
