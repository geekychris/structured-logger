# Structured Logging System Architecture

## Overview

This system provides a complete end-to-end structured logging solution with metadata-driven configuration. The architecture separates concerns into three main layers: **Logging**, **Streaming**, and **Storage**.

## High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    APPLICATION LAYER                              │
│  ┌──────────────────┐           ┌──────────────────┐            │
│  │   Java App       │           │   Python App     │            │
│  │                  │           │                  │            │
│  │ UserEventsLogger │           │ UserEventsLogger │            │
│  │ ApiMetricsLogger │           │ ApiMetricsLogger │            │
│  └────────┬─────────┘           └────────┬─────────┘            │
│           │                              │                       │
└───────────┼──────────────────────────────┼───────────────────────┘
            │                              │
            └──────────────┬───────────────┘
                           │ JSON over Kafka
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│                    STREAMING LAYER                                │
│                                                                   │
│                   ┌─────────────────────┐                        │
│                   │   Apache Kafka      │                        │
│                   │   Topics:           │                        │
│                   │   - user-events     │                        │
│                   │   - api-metrics     │                        │
│                   └──────────┬──────────┘                        │
│                              │                                   │
└──────────────────────────────┼───────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                    PROCESSING LAYER                               │
│                                                                   │
│        ┌────────────────────────────────────────────┐            │
│        │  Spark Structured Streaming                 │            │
│        │  StructuredLogConsumer                      │            │
│        │                                             │            │
│        │  - Reads log configs                        │            │
│        │  - Subscribes to Kafka topics               │            │
│        │  - Parses JSON with schema                  │            │
│        │  - Creates Iceberg tables                   │            │
│        │  - Writes to warehouse                      │            │
│        └─────────────────┬──────────────────────────┘            │
│                          │                                       │
└──────────────────────────┼───────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│                    STORAGE LAYER                                  │
│                                                                   │
│                   ┌─────────────────────┐                        │
│                   │  Apache Iceberg     │                        │
│                   │  Data Warehouse     │                        │
│                   │                     │                        │
│                   │  Tables:            │                        │
│                   │  - user_events      │                        │
│                   │  - api_metrics      │                        │
│                   │                     │                        │
│                   │  Format: Parquet    │                        │
│                   │  Features:          │                        │
│                   │  - Partitioning     │                        │
│                   │  - Sorting          │                        │
│                   │  - ACID             │                        │
│                   │  - Time Travel      │                        │
│                   └─────────────────────┘                        │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘
```

## Component Details

### 1. Configuration Layer

**Files**: `config-schema/`, `examples/*.json`

The heart of the system is the log configuration format. Each log type is defined once in JSON:

```json
{
  "name": "UserEvents",
  "version": "1.0.0",
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
  "fields": [...]
}
```

**Benefits**:
- Single source of truth
- Schema evolution tracking via version field
- Self-documenting with descriptions
- Enables code generation and automation

### 2. Code Generation Layer

**Files**: `generators/generate_loggers.py`

The generator reads log configs and produces:

#### Java Output
- Type-safe logger class extending `BaseStructuredLogger`
- Strongly-typed field parameters (Instant, LocalDate, etc.)
- Builder pattern support for optional fields
- Automatic JSON serialization via Jackson
- Proper resource management (AutoCloseable)

#### Python Output
- Type-hinted logger class with mypy support
- Optional fields with proper defaults
- Builder pattern for complex construction
- Context manager support
- Automatic JSON serialization with datetime handling

**Key Design Decisions**:
- Generated code is immutable and marked as "DO NOT EDIT"
- Version metadata embedded in generated code
- Partition key selection is smart (user_id > session_id > first field)
- Builder pattern available in both languages

### 3. Base Logger Layer

**Files**: 
- `java-logger/src/main/java/com/logging/BaseStructuredLogger.java`
- `python-logger/structured_logging/base_logger.py`

Provides common functionality:
- Kafka producer configuration (compression, batching, retries)
- JSON serialization with proper datetime handling
- Async publishing with callbacks
- Error handling and logging
- Resource cleanup

**Configuration**:
- Bootstrap servers from environment variable `KAFKA_BOOTSTRAP_SERVERS`
- Production-ready defaults (snappy compression, acks=1, retries=3)
- Configurable per-instance if needed

### 4. Streaming Layer

**Technology**: Apache Kafka

**Characteristics**:
- One topic per log type
- Configurable partitioning for parallelism
- Retention policies from config
- JSON message format for flexibility
- Key-based partitioning (typically user_id)

**Why Kafka**:
- Decouples producers from consumers
- High throughput, low latency
- Fault tolerance via replication
- Supports multiple consumers
- Acts as buffer during processing outages

### 5. Processing Layer

**Files**: `spark-consumer/src/main/java/com/logging/consumer/StructuredLogConsumer.java`

Spark Structured Streaming job (written in Java) that:

1. **Loads Configs**: Reads all log configs from directory
2. **Multi-Topic Processing**: One query per log config
3. **Schema Enforcement**: Applies config schema to JSON parsing
4. **Table Management**: Creates Iceberg tables with proper DDL
5. **Continuous Writing**: Streams data to warehouse with checkpoints

**Key Features**:
- Metadata-driven (no code changes for new log types)
- Exactly-once semantics via checkpoints
- Automatic table creation with partitioning
- Handles multiple configs in one job
- Monitoring via Spark UI

**Data Flow**:
```
Kafka → Read Stream → Parse JSON → Apply Schema → 
Add Metadata (_ingestion_timestamp, _ingestion_date) → 
Write to Iceberg → Checkpoint
```

### 6. Storage Layer

**Technology**: Apache Iceberg with Parquet

**Table Structure**:
- All fields from config
- Additional metadata: `_ingestion_timestamp`, `_ingestion_date`
- Partitioned by config-specified fields
- Sorted by config-specified fields

**Why Iceberg**:
- ACID transactions
- Schema evolution support
- Time travel queries
- Efficient pruning and filtering
- Works with Spark, Trino, Presto, etc.
- Hidden partitioning (users don't specify partition in queries)

## Data Flow Example

### 1. Application Logs Event

```java
try (UserEventsLogger logger = new UserEventsLogger()) {
    logger.log(
        Instant.now(),
        LocalDate.now(),
        "user_12345",
        "session_abc",
        "click",
        "/products/laptop",
        null,
        "desktop",
        1500L
    );
}
```

### 2. Logger Serializes to JSON

```json
{
  "timestamp": "2024-11-25T18:30:45.123Z",
  "event_date": "2024-11-25",
  "user_id": "user_12345",
  "session_id": "session_abc",
  "event_type": "click",
  "page_url": "/products/laptop",
  "device_type": "desktop",
  "duration_ms": 1500
}
```

### 3. Published to Kafka

- **Topic**: `user-events`
- **Key**: `user_12345` (for consistent partitioning)
- **Value**: JSON above
- **Partition**: Determined by hash(key) % num_partitions

### 4. Spark Consumes and Processes

- Reads from `user-events` topic
- Parses JSON using schema from config
- Adds `_ingestion_timestamp` = current_timestamp()
- Adds `_ingestion_date` = current_date()

### 5. Written to Iceberg

Table: `analytics.logs.user_events`

Partitioned by: `event_date=2024-11-25`, `event_type=click`

File: `s3://warehouse/analytics/logs/user_events/event_date=2024-11-25/event_type=click/data-00123.parquet`

### 6. Query from Warehouse

```sql
SELECT user_id, COUNT(*) as clicks
FROM analytics.logs.user_events
WHERE event_date = '2024-11-25'
  AND event_type = 'click'
GROUP BY user_id
ORDER BY clicks DESC
LIMIT 10;
```

## Scalability Considerations

### Application Tier
- Loggers are lightweight (just Kafka producer)
- Async publishing doesn't block application threads
- Producer batching amortizes network overhead

### Kafka Tier
- Horizontal scaling via partitions
- Each partition is independent
- Consumer groups for parallel processing
- Retention policies prevent unbounded growth

### Processing Tier
- Spark can scale to hundreds of executors
- Each Kafka partition maps to Spark task
- Checkpointing enables exactly-once semantics
- Multiple streaming queries can run concurrently

### Storage Tier
- Iceberg handles huge tables (petabytes)
- Partitioning enables partition pruning
- Sorting within files improves query performance
- File compaction prevents small file problem

## Operations

### Adding a New Log Type

1. Create config JSON file in `log-configs/`
2. Run generator: `python generators/generate_loggers.py log-configs/your_config.json`
3. Build applications with new logger
4. Deploy applications
5. Restart Spark consumer (automatically discovers new config via Docker volume mount)

Total time: ~10 minutes

**📖 For detailed step-by-step instructions, see: [ADDING_NEW_LOGGERS.md](ADDING_NEW_LOGGERS.md)**

**How Config Discovery Works**: The `log-configs/` directory is mounted into the Spark container via Docker volumes (`./log-configs:/opt/spark-apps/log-configs`). When the consumer starts, it reads ALL `.json` files from this directory - no manual copying required!

### Schema Evolution

1. Update version in config (e.g., 1.0.0 → 1.1.0)
2. Add/modify fields (Iceberg supports compatible changes)
3. Regenerate loggers
4. Deploy updated applications
5. Old and new schemas coexist (Iceberg handles it)

### Monitoring

- **Application**: SLF4J/Python logging for publish errors
- **Kafka**: Consumer lag metrics, topic metrics
- **Spark**: Streaming query metrics, UI at localhost:4040
- **Warehouse**: Query performance metrics from Trino/Presto

### Troubleshooting

**High lag**: Increase Spark executors or Kafka partitions  
**Publish failures**: Check Kafka connectivity, increase retries  
**Schema errors**: Verify config matches actual data  
**Storage issues**: Check Iceberg table health, run compaction

## Security Considerations

### In Transit
- Kafka SSL/TLS encryption
- SASL authentication

### At Rest
- S3/HDFS encryption
- Parquet encryption

### Access Control
- Kafka ACLs per topic
- Warehouse-level permissions (Hive metastore, Ranger, etc.)

### PII/Sensitive Data
- Consider field-level encryption
- Use separate topics for sensitive logs
- Apply retention policies

## Future Enhancements

1. **Schema Registry Integration**: Avro schemas with registry
2. **Dead Letter Queue**: Failed records to separate topic
3. **Metrics & Alerting**: Prometheus metrics, Grafana dashboards
4. **Data Quality**: Great Expectations validation in Spark
5. **Cost Optimization**: S3 lifecycle policies, Iceberg compaction
6. **Multi-Region**: Kafka MirrorMaker for disaster recovery

## Summary

This architecture provides:
- **Type Safety**: Generated code catches errors at compile time
- **Flexibility**: Easy to add new log types
- **Scalability**: Each component scales independently
- **Reliability**: Kafka buffering, Spark checkpointing, ACID storage
- **Performance**: Compression, batching, partitioning, columnar format
- **Observability**: Metadata, monitoring, time travel queries
