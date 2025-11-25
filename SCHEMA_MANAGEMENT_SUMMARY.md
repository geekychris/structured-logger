# Schema Management Implementation Summary

## Overview

The Structured Logging System now includes **automatic schema evolution** capabilities that detect and apply schema changes when log configs are updated.

## What Was Implemented

### 1. Automatic Schema Evolution in StructuredLogConsumer

**Location**: `spark-consumer/src/main/java/com/logging/consumer/StructuredLogConsumer.java`

**Changes**:
- Split `ensureTableExists()` into three methods:
  - `tableExists()` - Check if table already exists
  - `createNewTable()` - Create new table with initial schema
  - `evolveSchema()` - Compare existing schema with desired schema and apply changes

**Key Features**:
- ✅ **Automatic field addition** - New fields trigger `ALTER TABLE ADD COLUMN`
- ✅ **Type compatibility checking** - Warns about incompatible type changes
- ✅ **Safe type promotion** - Supports `int→long` and `float→double` widening
- ✅ **Field removal detection** - Warns when fields are removed from config
- ✅ **Backward compatibility** - Old data remains queryable after schema changes

**Example Output**:
```
Checking schema evolution for: local.analytics_logs.user_events
  + Adding new column: country_code STRING
Executing: ALTER TABLE local.analytics_logs.user_events ADD COLUMN country_code STRING
✓ Schema evolved successfully
```

### 2. Comprehensive Documentation

**[SCHEMA_EVOLUTION.md](SCHEMA_EVOLUTION.md)** (458 lines):
- Supported vs. unsupported schema changes
- Safe migration strategies
- Schema versioning with Iceberg
- Time travel and rollback capabilities
- Troubleshooting guide
- Best practices

**Summary Table**:

| Change Type | Automatic? | Backward Compatible? | Action Required |
|-------------|-----------|---------------------|-----------------|
| Add field | ✅ Yes | ✅ Yes | None - restart consumer |
| Widen type (int→long) | ✅ Yes | ✅ Yes | None - restart consumer |
| Make optional | ✅ Yes | ✅ Yes | None - restart consumer |
| Remove field | ⚠️ Warning | ✅ Yes (stays in table) | Update queries if needed |
| Rename field | ❌ No | ❌ No | Add new + deprecate old |
| Change type | ❌ No | ❌ No | Add new field |
| Change partitioning | ❌ No | ❌ No | Create new table |

### 3. Interactive Demo

**[examples/schema_evolution_demo.sh](examples/schema_evolution_demo.sh)**:
- Step-by-step demonstration of schema evolution
- Creates table with initial schema
- Adds new fields to config
- Restarts consumer to trigger evolution
- Shows mixed queries (old data with NULLs, new data with values)

**Usage**:
```bash
bash examples/schema_evolution_demo.sh
```

### 4. Test Data Generator Enhancement

**[examples/generate_test_data.py](examples/generate_test_data.py)**:
- Generate realistic test data for load testing
- Multiple modes: batch, continuous, timed
- Configurable event rate
- Documentation in [examples/README.md](examples/README.md)

## How It Works

### Schema Evolution Workflow

```
1. Consumer starts → loads log configs
   ↓
2. For each config:
   - Check if table exists
   ↓
3. If table exists:
   - Get current schema from Iceberg
   - Compare with desired schema from config
   - Identify differences:
     • New fields → ALTER TABLE ADD COLUMN
     • Type mismatches → Warning (keep current)
     • Removed fields → Info message (keep in table)
   ↓
4. Execute ALTER TABLE statements
   ↓
5. Table ready with evolved schema
```

### Iceberg Metadata Management

Iceberg maintains a metadata journal for each table:

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

Each schema change creates a new metadata version, enabling:
- **Time travel**: Query data as it existed at any snapshot
- **Schema history**: View all schema versions
- **Rollback**: Revert to previous schema if needed

## Usage Examples

### Example 1: Adding a New Field

**Before** - `log-configs/user_events.json`:
```json
{
  "fields": [
    {"name": "user_id", "type": "string"},
    {"name": "event_type", "type": "string"}
  ]
}
```

**After** - Add `country_code`:
```json
{
  "fields": [
    {"name": "user_id", "type": "string"},
    {"name": "event_type", "type": "string"},
    {"name": "country_code", "type": "string", "required": false}
  ]
}
```

**Steps**:
```bash
# 1. Update config
vim log-configs/user_events.json

# 2. Restart consumer
docker exec spark-master pkill -f StructuredLogConsumer
./start-consumer.sh

# Consumer output:
# Checking schema evolution for: local.analytics_logs.user_events
#   + Adding new column: country_code STRING
# Executing: ALTER TABLE local.analytics_logs.user_events ADD COLUMN country_code STRING
# ✓ Schema evolved successfully
```

**Result**:
- Old records: `country_code = NULL`
- New records: `country_code = 'US'` (or whatever value)
- All records queryable together

### Example 2: Type Promotion

**Before**:
```json
{"name": "response_time", "type": "int"}
```

**After**:
```json
{"name": "response_time", "type": "long"}
```

**Result**: Automatic promotion, old int values read as long

### Example 3: Incompatible Change (Type Change)

**Before**:
```json
{"name": "status_code", "type": "int"}
```

**After**:
```json
{"name": "status_code", "type": "string"}
```

**Consumer Output**:
```
  ! WARNING: Field 'status_code' type mismatch:
    Current: integer
    Desired: string
    Type changes are not supported - keeping current type
✓ Schema unchanged
```

**Solution**: Add new field with different name:
```json
{
  "fields": [
    {"name": "status_code", "type": "int"},          // Keep old
    {"name": "status_code_str", "type": "string"}    // Add new
  ]
}
```

## Querying Schema History

### View Current Schema
```sql
DESCRIBE TABLE iceberg.analytics_logs.user_events;
```

### View Schema History (Spark SQL)
```sql
SELECT * FROM local.analytics_logs.user_events.history;
```

### Time Travel Query
```sql
-- Query as of specific snapshot
SELECT * FROM iceberg.analytics_logs.user_events 
FOR VERSION AS OF 7864623715751563766;

-- Query as of timestamp
SELECT * FROM iceberg.analytics_logs.user_events 
FOR TIMESTAMP AS OF TIMESTAMP '2025-11-25 12:00:00';
```

### Rollback Schema
```sql
-- Find snapshot ID
SELECT snapshot_id, committed_at 
FROM local.analytics_logs.user_events.snapshots 
ORDER BY committed_at DESC;

-- Rollback to previous version
CALL local.system.rollback_to_snapshot(
  'analytics_logs.user_events', 
  123456789
);
```

## Best Practices

### 1. Always Make New Fields Optional
```json
{"name": "new_field", "type": "string", "required": false}
```
Reason: Old data won't have values.

### 2. Version Your Configs
```bash
git commit -am "Add country_code field to user_events schema"
```

### 3. Test in Development First
```bash
# Generate small dataset
python3 examples/generate_test_data.py --count 10

# Update schema
vim log-configs/user_events.json

# Restart and verify
./start-consumer.sh
docker logs spark-master | grep "Schema evolved"
```

### 4. Use Dual Fields for Type Changes
```json
{
  "fields": [
    {"name": "user_id", "type": "string"},       // Old
    {"name": "account_id", "type": "string"}     // New (migrate to this)
  ]
}
```

### 5. Document Breaking Changes
If making incompatible changes, document in CHANGELOG.md and notify consumers.

## Migration Strategies

### Strategy 1: Gradual (Recommended)
- Add new optional field
- Populate both old and new fields temporarily
- Update queries to use new field
- Remove old field after migration period

### Strategy 2: New Table
- Create new config with `_v2` suffix
- Backfill data if needed
- Switch traffic to new table
- Deprecate old table

### Strategy 3: Manual ALTER
For complex changes, use Iceberg SQL directly:
```sql
ALTER TABLE local.analytics_logs.user_events 
ADD COLUMN geo_region STRING;
```

## Testing

### Test Schema Evolution
```bash
# Run interactive demo
bash examples/schema_evolution_demo.sh

# Or manually:
# 1. Add field to config
# 2. Restart consumer
# 3. Check logs for "Schema evolved"
# 4. Verify schema: DESCRIBE TABLE
```

### Verify Backward Compatibility
```sql
-- Old records should have NULL for new fields
SELECT 
  _ingestion_timestamp,
  user_id,
  new_field
FROM iceberg.analytics_logs.user_events
ORDER BY _ingestion_timestamp
LIMIT 10;
```

## Troubleshooting

### Consumer Won't Start After Config Change

**Check logs**:
```bash
docker logs spark-master | tail -50
```

**Common causes**:
1. Incompatible type change → Revert or add as new field
2. JSON syntax error → Validate with `jq`
3. Missing required values

### Schema Not Evolving

**Verify**:
1. Consumer restarted after config change
2. Config file in correct location
3. Table already exists
4. Consumer has write permissions

### Old Data Shows Unexpected Values

**Expected behavior**: Old data has NULL for new optional fields.

**Verify**:
```sql
SELECT 
  event_date,
  new_field,
  COUNT(*) 
FROM iceberg.analytics_logs.user_events 
GROUP BY event_date, new_field;
```

## Summary

The schema evolution implementation provides:

✅ **Automatic detection and application** of safe schema changes  
✅ **Backward compatibility** - old data remains queryable  
✅ **Safety checks** - warns about incompatible changes  
✅ **Full history** - Iceberg tracks all schema versions  
✅ **Time travel** - query any historical schema  
✅ **Zero downtime** - schema changes don't require data rewrite  

The system handles **additive changes** (new fields) automatically while providing clear guidance for **complex changes** (type changes, renames, removals).

See [SCHEMA_EVOLUTION.md](SCHEMA_EVOLUTION.md) for complete documentation.
