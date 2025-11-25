# Apache Iceberg + Hive Metastore Architecture

This document explains how Apache Iceberg and Hive Metastore work together in the Structured Logging System.

## TL;DR

**Yes, this system uses 100% Apache Iceberg!**

- ✅ **Data format**: Apache Iceberg (Parquet files + Iceberg metadata)
- ✅ **Storage**: S3/MinIO with Iceberg file layout
- ✅ **Table format**: Apache Iceberg v1.4.2
- ✅ **Catalog**: Hive Metastore (just for table discovery)

Hive Metastore is only used as a "phone book" - all the actual data operations use pure Iceberg.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                    Spark Consumer                        │
│         Writes using Apache Iceberg APIs                 │
└─────────────────┬───────────────────────────────────────┘
                  │
                  ├──────────────┐
                  │              │
                  ▼              ▼
    ┌─────────────────────┐  ┌──────────────────────┐
    │  Hive Metastore     │  │   MinIO/S3           │
    │  (Catalog/Registry) │  │   (Data Storage)     │
    │                     │  │                      │
    │  Stores:            │  │  Stores:             │
    │  - Table names      │  │  - Parquet files     │
    │  - S3 locations     │  │  - Iceberg metadata  │
    │  - Schema registry  │  │  - Snapshot files    │
    │  - Partition info   │  │  - Manifest lists    │
    └─────────────────────┘  └──────────────────────┘
             │                          │
             │                          │
             └──────────┬───────────────┘
                        │
                        ▼
              ┌─────────────────┐
              │      Trino      │
              │  (Query Engine) │
              └─────────────────┘
```

## The Components

### Apache Iceberg = Table Format

**Purpose**: Modern table format with ACID guarantees

**Responsibilities**:
- Organizing data files in S3
- Managing metadata (schemas, partitions, snapshots)
- ACID transaction support
- Time travel and versioning
- Schema evolution
- Hidden partitioning

**What it stores in S3**:
```
s3://warehouse/analytics_logs/user_events/
├── metadata/                           ← Iceberg metadata
│   ├── v1.gz.metadata.json            ← Table metadata (schema, partitions)
│   ├── v2.gz.metadata.json            ← Updated metadata (version 2)
│   ├── v3.gz.metadata.json            ← Updated metadata (version 3)
│   ├── snap-123456-1-abc.avro         ← Snapshot manifest (list of files)
│   ├── snap-789012-1-def.avro         ← Another snapshot
│   └── version-hint.text              ← Points to current version
└── data/                               ← Actual data
    └── event_date=2025-11-25/
        ├── event_type=click/
        │   └── 00001-xxx.parquet      ← Parquet data file
        ├── event_type=page_view/
        │   └── 00002-xxx.parquet
        └── event_type=purchase/
            └── 00003-xxx.parquet
```

### Hive Metastore = Catalog

**Purpose**: Central registry for table locations (like DNS for data)

**Responsibilities**:
- Registering table names
- Storing table locations (S3 paths)
- Making tables discoverable across engines (Spark, Trino, Presto)
- Schema registry (high-level only)

**What it stores in PostgreSQL**:
```sql
-- Example of what Hive Metastore tracks
TABLE: analytics_logs.user_events
LOCATION: s3://warehouse/analytics_logs/user_events
FORMAT: iceberg
METADATA_LOCATION: s3://warehouse/.../metadata/v3.gz.metadata.json
```

**What it does NOT store**:
- ❌ Actual data files
- ❌ Parquet files
- ❌ Detailed Iceberg metadata
- ❌ Snapshots or versions

Think of it as:
- **Hive Metastore** = Yellow Pages (phone book)
- **Iceberg** = The actual business at that address
- **S3** = The building where the business operates

### MinIO/S3 = Storage

**Purpose**: Object storage for everything

**Stores**:
- Parquet data files (columnar format)
- Iceberg metadata files (JSON, Avro)
- Iceberg snapshot manifests
- Checkpoint data for Spark

## Why Both?

### Why Iceberg?

**Modern Table Format**:
- ACID transactions (atomic commits, no partial writes)
- Time travel (query historical data)
- Schema evolution (add/remove columns safely)
- Partition evolution (change partitioning without rewriting)
- Hidden partitioning (no partition columns in SELECT)
- Snapshot isolation (consistent reads during writes)
- Better than plain Parquet or Hive tables

**Example Iceberg Features**:
```sql
-- Time travel to specific snapshot
SELECT * FROM iceberg.analytics_logs.user_events 
FOR VERSION AS OF 7864623715751563766;

-- View all snapshots
SELECT * FROM iceberg.analytics_logs.user_events.snapshots;

-- View data files
SELECT file_path, record_count, file_size_in_bytes 
FROM iceberg.analytics_logs.user_events.files;

-- View partition info
SELECT * FROM iceberg.analytics_logs.user_events.partitions;
```

### Why Hive Metastore?

**Industry Standard Catalog**:
- Supported by Spark, Trino, Presto, Athena, Hive
- Makes tables discoverable across different engines
- Provides a common namespace
- Mature, well-tested, widely adopted

**Alternatives** (not used here):
- AWS Glue Catalog (AWS managed)
- Databricks Unity Catalog
- Nessie Catalog (Git-like for data)
- REST Catalog (HTTP API)

We use Hive Metastore because:
- ✅ Works with all query engines
- ✅ Self-hosted (no AWS dependency)
- ✅ Free and open-source
- ✅ Proven at scale

## Data Flow

### Writing Data

```
1. Application logs event
   ↓
2. Logger serializes to JSON → Kafka
   ↓
3. Spark Consumer reads from Kafka
   ↓
4. Spark writes using Iceberg API
   ↓
5. Iceberg:
   - Writes Parquet file to S3
   - Creates snapshot manifest
   - Updates metadata file (v3.gz.metadata.json)
   - Atomic commit (all or nothing)
   ↓
6. Iceberg updates Hive Metastore:
   - "user_events table is at s3://warehouse/.../metadata/v3.gz.metadata.json"
```

### Reading Data

```
1. Trino receives query: SELECT * FROM iceberg.analytics_logs.user_events
   ↓
2. Trino asks Hive Metastore: "Where is user_events table?"
   ↓
3. Hive Metastore replies: "s3://warehouse/.../metadata/v3.gz.metadata.json"
   ↓
4. Trino reads Iceberg metadata from S3
   ↓
5. Iceberg metadata tells Trino which Parquet files to read
   ↓
6. Trino reads Parquet files directly from S3
   ↓
7. Returns results to user
```

## Code Configuration

### Spark Consumer Configuration

```java
SparkSession spark = SparkSession.builder()
    .appName("StructuredLogConsumer")
    // Enable Iceberg extensions
    .config("spark.sql.extensions", 
            "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
    
    // Define catalog named "local"
    .config("spark.sql.catalog.local", 
            "org.apache.iceberg.spark.SparkCatalog")  // ← ICEBERG!
    
    // Use Hive as the catalog backend
    .config("spark.sql.catalog.local.type", "hive")   // ← Hive Metastore
    
    // Hive Metastore location
    .config("spark.sql.catalog.local.uri", 
            "thrift://hive-metastore:9083")
    
    // S3 warehouse location
    .config("spark.sql.catalog.local.warehouse", 
            "s3a://warehouse/")
    
    // Use Iceberg's S3FileIO (AWS SDK v2)
    .config("spark.sql.catalog.local.io-impl", 
            "org.apache.iceberg.aws.s3.S3FileIO")  // ← ICEBERG!
    
    .getOrCreate();
```

### What This Means

- **`SparkCatalog`**: All operations use Iceberg APIs
- **`type=hive`**: Catalog metadata stored in Hive Metastore
- **`S3FileIO`**: Iceberg handles S3 I/O
- **Result**: Full Iceberg features + Hive discoverability

## Verification

### Check Iceberg Metadata in S3

```bash
# View Iceberg metadata files
docker exec minio mc ls myminio/warehouse/analytics_logs/user_events/metadata/

# You'll see:
# v1.gz.metadata.json  ← Iceberg metadata (NOT Hive format)
# v2.gz.metadata.json  ← Schema version 2
# v3.gz.metadata.json  ← Schema version 3
# snap-*.avro          ← Iceberg snapshot manifests
# version-hint.text    ← Current version pointer
```

### Check Table in Hive Metastore

```bash
# Tables visible via Hive Metastore
docker exec -it trino trino --execute "SHOW TABLES IN iceberg.analytics_logs"

# Returns:
# api_metrics
# user_events
```

### Check Iceberg Features

```sql
-- Time travel (Iceberg-specific)
SELECT snapshot_id, committed_at, operation 
FROM iceberg.analytics_logs.user_events.snapshots 
ORDER BY committed_at DESC;

-- View data files (Iceberg-specific)
SELECT file_path, record_count 
FROM iceberg.analytics_logs.user_events.files;

-- These work because it's REAL Iceberg!
```

## File Format Details

### Iceberg Metadata File (v1.gz.metadata.json)

```json
{
  "format-version": 1,
  "table-uuid": "abc-123-def-456",
  "location": "s3://warehouse/analytics_logs/user_events",
  "last-updated-ms": 1700000000000,
  "last-column-id": 12,
  "schema": {
    "type": "struct",
    "fields": [
      {"id": 1, "name": "timestamp", "required": true, "type": "timestamp"},
      {"id": 2, "name": "user_id", "required": true, "type": "string"},
      {"id": 3, "name": "event_type", "required": true, "type": "string"}
    ]
  },
  "partition-spec": [
    {"name": "event_date", "transform": "identity", "source-id": 4},
    {"name": "event_type", "transform": "identity", "source-id": 3}
  ],
  "current-snapshot-id": 7864623715751563766,
  "snapshots": [
    {
      "snapshot-id": 7864623715751563766,
      "timestamp-ms": 1700000000000,
      "manifest-list": "s3://.../snap-7864623715751563766-1-abc.avro"
    }
  ]
}
```

**This is pure Iceberg format!** Not Hive.

### Snapshot Manifest (snap-*.avro)

Binary Avro file containing:
- List of all data files in this snapshot
- File locations (S3 paths)
- Record counts
- Partition values
- File metrics (min/max values, null counts)

### Data Files (*.parquet)

Standard Parquet columnar format:
- Compressed (Snappy)
- Columnar layout
- Predicate pushdown support
- Schema embedded

## Comparison

### Hive Tables vs Iceberg Tables

| Feature | Hive Tables | Iceberg Tables |
|---------|-------------|----------------|
| Format | Parquet/ORC | Parquet + Metadata |
| ACID | Limited | Full ACID |
| Time Travel | ❌ | ✅ |
| Schema Evolution | Manual | Automatic |
| Partition Evolution | ❌ Rewrite needed | ✅ No rewrite |
| Hidden Partitioning | ❌ | ✅ |
| Snapshot Isolation | ❌ | ✅ |
| Compaction | Manual | Built-in |
| Metadata Layer | Directory listing | Rich metadata |

### What We're Using

We use **Iceberg tables** registered in **Hive Metastore**:
- ✅ Best of both worlds
- ✅ Iceberg features (ACID, time travel, etc.)
- ✅ Hive discoverability (works with all engines)

## Query Examples

### Standard Queries (work via Trino/Spark)

```sql
-- Simple select
SELECT * FROM iceberg.analytics_logs.user_events 
WHERE event_date = DATE '2025-11-25'
LIMIT 10;

-- Aggregation
SELECT event_type, COUNT(*) as count
FROM iceberg.analytics_logs.user_events
GROUP BY event_type;
```

### Iceberg-Specific Queries

```sql
-- View table history (Iceberg)
SELECT * FROM iceberg.analytics_logs.user_events.history;

-- View snapshots (Iceberg)
SELECT snapshot_id, committed_at, operation, summary
FROM iceberg.analytics_logs.user_events.snapshots
ORDER BY committed_at DESC;

-- Time travel (Iceberg)
SELECT COUNT(*) FROM iceberg.analytics_logs.user_events
FOR VERSION AS OF 7864623715751563766;

-- View data files (Iceberg)
SELECT 
    partition,
    file_path,
    file_size_in_bytes / 1024 / 1024 as size_mb,
    record_count
FROM iceberg.analytics_logs.user_events.files
ORDER BY file_size_in_bytes DESC;

-- View partitions (Iceberg)
SELECT * FROM iceberg.analytics_logs.user_events.partitions;

-- View manifests (Iceberg)
SELECT * FROM iceberg.analytics_logs.user_events.manifests;
```

## Benefits of This Architecture

### For Development

1. **Local Testing**: Full Iceberg features with MinIO
2. **Cloud Compatible**: Same code works with AWS S3
3. **Standard Tools**: Works with Spark, Trino, Presto, DBeaver, etc.
4. **Version Control**: Time travel and snapshots built-in

### For Production

1. **ACID Guarantees**: No partial writes or dirty reads
2. **Schema Evolution**: Add columns without breaking readers
3. **Partition Evolution**: Optimize partitioning over time
4. **Performance**: Hidden partitioning, predicate pushdown
5. **Maintenance**: Built-in compaction and cleanup

### For Data Engineering

1. **Unified Catalog**: One place to find all tables
2. **Multi-Engine**: Query with Spark, Trino, or Python
3. **Time Travel**: Debug issues by querying old data
4. **Audit Trail**: Every change tracked in snapshots
5. **Safe Updates**: Atomic commits prevent corruption

## Common Misconceptions

### ❌ "This is a Hive table"
**✅ Correct**: It's an Iceberg table registered in Hive Metastore

### ❌ "Data is stored in Hive format"
**✅ Correct**: Data is stored in Iceberg format (Parquet + Iceberg metadata)

### ❌ "Hive Metastore stores the data"
**✅ Correct**: Hive Metastore only stores the catalog (table locations)

### ❌ "We need Hive for queries"
**✅ Correct**: We need Iceberg for queries; Hive just helps find the tables

## Summary

This system uses:

1. **Apache Iceberg** (v1.4.2)
   - Table format
   - Data organization
   - ACID transactions
   - Time travel
   - Schema evolution
   - 100% of data operations

2. **Hive Metastore**
   - Table registry/catalog
   - Makes tables discoverable
   - Industry standard
   - ~0% of data operations

3. **S3/MinIO**
   - Storage backend
   - Stores Iceberg metadata
   - Stores Parquet data files

**Bottom line**: This is a pure Iceberg implementation using Hive Metastore as a catalog service.

## Further Reading

- **Apache Iceberg Docs**: https://iceberg.apache.org/docs/latest/
- **Iceberg Spec**: https://iceberg.apache.org/spec/
- **Trino Iceberg Connector**: https://trino.io/docs/current/connector/iceberg.html
- **Spark Iceberg Integration**: https://iceberg.apache.org/docs/latest/spark-writes/
