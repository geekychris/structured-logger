# Schema Evolution in Structured Logging System

This document explains how schema changes are managed automatically by the Structured Log Consumer.

## Overview

The system uses **Apache Iceberg's native schema evolution** capabilities to handle changes to log configurations without requiring manual table alterations or data migration.

### Key Principles

1. **Automatic Detection**: Consumer detects schema changes on startup
2. **Backward Compatible**: Old data remains queryable with new schema
3. **Safe Evolution**: Only compatible changes are allowed
4. **Schema History**: Iceberg maintains full schema version history

## Supported Schema Changes

### ✅ Safe Changes (Automatically Applied)

#### 1. Adding New Fields

**Example**: Add a new field to track user location

```json
// Before: user_events.json
{
  "fields": [
    {"name": "user_id", "type": "string", "required": true},
    {"name": "event_type", "type": "string", "required": true}
  ]
}

// After: user_events.json  
{
  "fields": [
    {"name": "user_id", "type": "string", "required": true},
    {"name": "event_type", "type": "string", "required": true},
    {"name": "country_code", "type": "string", "required": false}  // NEW
  ]
}
```

**What Happens**:
```
Checking schema evolution for: local.analytics_logs.user_events
  + Adding new column: country_code STRING
Executing: ALTER TABLE local.analytics_logs.user_events ADD COLUMN country_code STRING
✓ Schema evolved successfully
```

**Old Data**: NULL for new field (safe - field is nullable)
**New Data**: Contains new field values

#### 2. Widening Numeric Types

**Example**: Change response_time from int to long for larger values

```json
// Before
{"name": "response_time", "type": "int", "required": true}

// After  
{"name": "response_time", "type": "long", "required": true}
```

**Supported Widenings**:
- `int` → `long`
- `float` → `double`

**What Happens**: Type promotion is applied automatically. Old data is read as the wider type.

#### 3. Making Required Fields Optional

**Example**: Allow null values for optional metadata

```json
// Before
{"name": "user_agent", "type": "string", "required": true}

// After
{"name": "user_agent", "type": "string", "required": false}
```

**What Happens**: Field becomes nullable. Old data (all non-null) remains valid.

### ⚠️ Unsupported Changes (Warnings)

#### 1. Removing Fields

```json
// Before
{
  "fields": [
    {"name": "user_id", "type": "string"},
    {"name": "session_id", "type": "string"},  // Will be removed
    {"name": "event_type", "type": "string"}
  ]
}

// After: session_id removed from config
```

**What Happens**:
```
  ! INFO: Field 'session_id' removed from config but will remain in table
    (Iceberg preserves schema history - old data remains queryable)
✓ Schema unchanged
```

**Why**: Iceberg doesn't delete columns to preserve historical data integrity.

**Workaround**: 
- Field remains in table but won't be populated by new data
- Query with `SELECT user_id, event_type FROM ...` to exclude it
- Use `ALTER TABLE ... DROP COLUMN` manually if truly needed (rare)

#### 2. Type Changes (Incompatible)

```json
// Before
{"name": "status_code", "type": "int"}

// After  
{"name": "status_code", "type": "string"}  // Incompatible
```

**What Happens**:
```
  ! WARNING: Field 'status_code' type mismatch:
    Current: integer
    Desired: string
    Type changes are not supported - keeping current type
✓ Schema unchanged
```

**Why**: Cannot convert existing int data to string automatically.

**Workaround**:
1. Add new field: `status_code_v2` with type `string`
2. Deprecated old field in documentation
3. Update log generators to use new field
4. Eventually drop old field after migration period

### ❌ Dangerous Changes (Not Supported)

#### 1. Changing Partition Columns

**Example**: Changing partitioning scheme

```json
// Before
"warehouse": {
  "partitionBy": ["event_date"]
}

// After
"warehouse": {
  "partitionBy": ["event_date", "event_type"]  // Adding partition
}
```

**Impact**: Requires table recreation and data rewrite.

**Procedure**:
1. Create new table with new partitioning
2. Backfill data from old table
3. Switch consumers to new table
4. Drop old table

#### 2. Renaming Fields

**Example**: Rename for clarity

```json
// Before
{"name": "user_id", "type": "string"}

// After
{"name": "account_id", "type": "string"}  // Renamed
```

**Impact**: Treated as field removal + addition (breaks queries).

**Procedure**:
1. Add new field with new name
2. Populate both fields during migration
3. Update all queries to use new name
4. Eventually remove old field from config

## Schema Evolution Workflow

### Automatic Evolution (Default)

```bash
# 1. Update log config
vim log-configs/user_events.json

# 2. Restart consumer - schema evolution happens automatically
docker exec spark-master pkill -f StructuredLogConsumer
./start-consumer.sh

# Consumer output:
# Checking schema evolution for: local.analytics_logs.user_events
#   + Adding new column: new_field STRING
# Executing: ALTER TABLE local.analytics_logs.user_events ADD COLUMN new_field STRING
# ✓ Schema evolved successfully
```

### Manual Schema Changes

For complex changes, use Iceberg SQL directly:

```sql
-- Add column
ALTER TABLE local.analytics_logs.user_events 
ADD COLUMN geo_region STRING;

-- Update partition spec (creates new partition spec - old data unchanged)
ALTER TABLE local.analytics_logs.user_events 
ADD PARTITION FIELD bucket(10, user_id);

-- Drop column (careful - loses data!)
ALTER TABLE local.analytics_logs.user_events 
DROP COLUMN deprecated_field;

-- View schema history
SELECT * FROM local.analytics_logs.user_events.snapshots;
```

## Schema Versioning with Iceberg

### How It Works

Iceberg maintains a **metadata journal** for each table:

```
s3://warehouse/analytics_logs/user_events/
├── metadata/
│   ├── v1.metadata.json          # Initial schema
│   ├── v2.metadata.json          # After adding field
│   ├── v3.metadata.json          # After another change
│   └── snap-*.avro               # Snapshot manifests
└── data/
    └── event_date=2025-11-25/    # Parquet files
```

Each metadata version contains:
- Complete schema definition
- Partition specification
- Sort order
- Snapshot information
- Creation timestamp

### Querying Schema History

```sql
-- Current schema
DESCRIBE TABLE iceberg.analytics_logs.user_events;

-- Schema evolution history (Spark SQL)
SELECT * FROM local.analytics_logs.user_events.history;

-- View specific snapshot schema
SELECT * FROM local.analytics_logs.user_events.snapshots;
```

### Time Travel (Query Old Schema)

```sql
-- Query data as of a specific snapshot
SELECT * FROM iceberg.analytics_logs.user_events 
FOR VERSION AS OF 123456789;

-- Query data as of a timestamp
SELECT * FROM iceberg.analytics_logs.user_events 
FOR TIMESTAMP AS OF TIMESTAMP '2025-11-25 12:00:00';
```

## Best Practices

### 1. Additive Changes Only

**✅ DO**: Add new optional fields
```json
{"name": "new_feature", "type": "string", "required": false}
```

**❌ DON'T**: Remove or rename fields without planning

### 2. Use Optional Fields for New Data

**✅ DO**: Make new fields optional (nullable)
```json
{"name": "new_metric", "type": "double", "required": false}
```

Reason: Old data won't have values - nulls are expected.

### 3. Version Your Configs

Track changes in git:
```bash
git commit -am "Add country_code field to user_events"
```

### 4. Test Schema Changes in Dev

```bash
# Test with small dataset first
python3 examples/generate_test_data.py --count 10

# Verify schema evolution
docker logs spark-master | grep "Schema evolved"

# Query results
docker exec -it trino trino --execute \
  "DESCRIBE iceberg.analytics_logs.user_events"
```

### 5. Document Breaking Changes

If you must make incompatible changes:
1. Document in CHANGELOG.md
2. Notify consumers (applications reading from Iceberg)
3. Provide migration period with dual field support

## Migration Strategies

### Strategy 1: Dual Field (Recommended)

For type changes or renames:

```json
{
  "fields": [
    {"name": "user_id", "type": "string"},           // Old (keep temporarily)
    {"name": "account_id", "type": "string"},        // New (populate going forward)
    {"name": "status_code", "type": "int"},          // Old
    {"name": "status_code_str", "type": "string"}    // New
  ]
}
```

**Timeline**:
- Week 1-2: Populate both fields
- Week 3-4: Update queries to use new fields
- Week 5+: Remove old fields from config

### Strategy 2: New Table

For major schema overhauls:

```bash
# Create new config
cp log-configs/user_events.json log-configs/user_events_v2.json

# Update table name and schema
vim log-configs/user_events_v2.json
# "tableName": "analytics_logs.user_events_v2"

# Restart consumer to create new table
./start-consumer.sh

# Backfill if needed
INSERT INTO analytics_logs.user_events_v2 
SELECT /* transformed columns */ FROM analytics_logs.user_events;
```

### Strategy 3: Partition Evolution

Change partitioning without recreating table:

```sql
-- Iceberg supports partition evolution
ALTER TABLE local.analytics_logs.user_events 
ADD PARTITION FIELD days(timestamp);

-- New data uses new partitioning
-- Old data keeps old partitioning
-- Both queryable together!
```

## Troubleshooting

### Consumer Won't Start After Config Change

**Check logs**:
```bash
docker logs spark-master | tail -50
```

**Common issues**:
1. Incompatible type change → Revert config or add as new field
2. Syntax error in JSON → Validate with `jq`
3. Partition field removed → Cannot change partition columns

### Old Data Has NULLs for New Field

**Expected behavior** - this is correct!

```sql
-- Verify: old rows have NULL, new rows have values
SELECT 
  event_date,
  new_field,
  COUNT(*) 
FROM iceberg.analytics_logs.user_events 
GROUP BY event_date, new_field;
```

### Need to Revert Schema Change

Iceberg supports schema rollback:

```sql
-- Find previous snapshot
SELECT snapshot_id, committed_at 
FROM local.analytics_logs.user_events.snapshots 
ORDER BY committed_at DESC;

-- Rollback to previous snapshot
CALL local.system.rollback_to_snapshot(
  'analytics_logs.user_events', 
  123456789
);
```

## Advanced: Schema Registry Integration

For enterprise deployments, consider integrating with **Confluent Schema Registry**:

```java
// Validate schema compatibility before applying
SchemaRegistryClient client = new CachedSchemaRegistryClient("http://schema-registry:8081", 100);
Schema newSchema = new Schema.Parser().parse(schemaJson);

// Check compatibility
boolean compatible = client.testCompatibility(topic, newSchema);
if (!compatible) {
    throw new SchemaIncompatibleException("Schema change breaks compatibility");
}
```

This adds an additional layer of validation before Iceberg schema evolution.

## Summary

| Change Type | Automatic? | Backward Compatible? | Action Required |
|-------------|-----------|---------------------|-----------------|
| Add field | ✅ Yes | ✅ Yes | None - restart consumer |
| Widen type (int→long) | ✅ Yes | ✅ Yes | None - restart consumer |
| Make optional | ✅ Yes | ✅ Yes | None - restart consumer |
| Remove field | ⚠️ Warning | ✅ Yes (stays in table) | Update queries if needed |
| Rename field | ❌ No | ❌ No | Add new + deprecate old |
| Change type | ❌ No | ❌ No | Add new field |
| Change partitioning | ❌ No | ❌ No | Create new table |

The system is designed to handle **additive schema changes** automatically while preserving data integrity and query compatibility.
