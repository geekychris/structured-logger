# Hive Metastore Configuration Fix

## Summary

Fixed the Hive Metastore configuration and added automatic schema initialization.

## Changes Made

### 1. Created `docker/init-metastore.sh`

Added initialization script that:
- Waits for PostgreSQL to be ready
- Checks if schema is already initialized
- Initializes schema if needed using `schematool`
- Starts the Hive Metastore service

### 2. Updated `docker/Dockerfile.hive-metastore`

- Added PostgreSQL client installation for `pg_isready`
- Copied init script and made it executable
- Set custom entrypoint to use init script

### 3. Created `docker/hive-conf/hive-site.xml`

Configured Hive to use PostgreSQL:
- Connection URL: `jdbc:postgresql://postgres:5432/metastore`
- Driver: `org.postgresql.Driver`
- Username: `hive`
- Password: `hivepassword`

### 4. Updated `docker-compose.yml`

Fixed password consistency:
- Changed PostgreSQL password from `hive` to `hivepassword`
- Updated `SERVICE_OPTS` environment variable to match

## How It Works

1. **On first startup**:
   - PostgreSQL container starts and creates empty `metastore` database
   - Hive Metastore container starts
   - Init script waits for PostgreSQL
   - Init script runs `schematool -initSchema`
   - Hive Metastore service starts with initialized schema

2. **On subsequent startups**:
   - Init script detects existing schema
   - Skips initialization
   - Starts Hive Metastore service directly

## Verification

Check that everything is working:

```bash
# Check Hive Metastore is running
docker-compose ps hive-metastore

# View initialization logs
docker logs hive-metastore | grep -E "(PostgreSQL|schema|Starting)"

# Expected output:
# PostgreSQL is up!
# Schema initialization complete!
# Starting Hive Metastore service...
```

## Database Schema

The initialized PostgreSQL schema includes:
- `TBLS` - Table metadata
- `COLUMNS_V2` - Column definitions  
- `PARTITIONS` - Partition information
- `SDS` - Storage descriptors
- `SERDES` - Serialization/deserialization info
- And many more Hive Metastore tables

View tables:
```bash
docker exec postgres psql -U hive -d metastore -c "\dt"
```

## Connection Details

- **Host**: `hive-metastore` (Docker network) / `localhost:9083` (from host)
- **Protocol**: Thrift
- **URI**: `thrift://hive-metastore:9083`
- **Database**: PostgreSQL at `postgres:5432/metastore`

## Troubleshooting

### Schema initialization fails

Check PostgreSQL is running:
```bash
docker exec postgres pg_isready -U hive
```

### Metastore won't start

Check logs:
```bash
docker logs hive-metastore --tail 50
```

### Need to reinitialize schema

```bash
# Drop and recreate
docker-compose down -v
docker-compose up -d
```

## Files Modified

- `docker/Dockerfile.hive-metastore` - Added pg-client, init script
- `docker/init-metastore.sh` - Created initialization script
- `docker/hive-conf/hive-site.xml` - Created Hive configuration
- `docker-compose.yml` - Fixed password consistency

## Result

✅ Hive Metastore now starts reliably with initialized PostgreSQL schema  
✅ Schema persists across container restarts  
✅ Automatic initialization on first startup  
✅ Compatible with Spark and Trino Iceberg catalogs
