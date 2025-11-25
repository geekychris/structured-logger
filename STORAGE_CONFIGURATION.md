# Storage Configuration

## Overview

The Structured Logging System uses **host directories** for all persistent data:
- **MinIO**: Iceberg table data (Parquet, metadata)
- **PostgreSQL**: Hive Metastore database
- **Kafka**: Message logs and offsets
- **Zookeeper**: Coordination data

All data survives container restarts and `docker-compose down -v`.

## Storage Locations

### MinIO/S3 Storage (Host Directory)

**Location**: `./data/minio/`

**Contains**:
- Iceberg table data (Parquet files)
- Iceberg metadata (JSON, Avro)
- Table snapshots
- Partition data

**Structure**:
```
data/
├── minio/                # MinIO/S3 object storage
│   ├── .minio.sys/       # MinIO system files
│   └── warehouse/        # Iceberg warehouse root
│       └── analytics_logs/
│           ├── user_events/
│           │   ├── data/     # Parquet files
│           │   └── metadata/ # Iceberg metadata
│           └── api_metrics/
│               ├── data/
│               └── metadata/
├── postgres/             # PostgreSQL database
│   ├── base/             # Database files
│   ├── global/           # Cluster-wide data
│   ├── pg_wal/           # Write-ahead logs
│   └── pgdata/           # Hive metastore data
├── kafka/                # Kafka message logs
│   ├── api-metrics-0/    # Topic partition data
│   ├── user-events-0/
│   └── meta.properties
└── zookeeper/            # Coordination data
    ├── data/             # Zookeeper snapshots
    └── log/              # Transaction logs
```

**Benefits**:
- ✅ Survives `docker-compose down`
- ✅ Survives `docker-compose down -v`
- ✅ Easy to backup (just copy directory)
- ✅ Easy to inspect (files on host)
- ✅ Portable (move directory to any machine)

### PostgreSQL (Host Directory)

**Location**: `./data/postgres/`

**Contains**:
- Hive Metastore database
- Table catalog
- Column metadata
- Partition information

**Benefits**:
- ✅ Survives all Docker operations
- ✅ Easy to backup
- ✅ Can be inspected with PostgreSQL tools

### Kafka (Host Directory)

**Location**: `./data/kafka/`

**Contains**:
- Topic data and logs
- Consumer offsets
- Partition metadata

**Benefits**:
- ✅ Message persistence across restarts
- ✅ No data loss on container recreation

### Zookeeper (Host Directory)

**Location**: `./data/zookeeper/`

**Contains**:
- Coordination data
- Kafka metadata
- Leader election state

**Benefits**:
- ✅ Cluster state persistence
- ✅ Faster restarts

## Common Operations

### View Data on Host

```bash
# List all tables
ls -la data/minio/warehouse/

# Check a specific table
ls -la data/minio/warehouse/analytics_logs/user_events/

# View Parquet files
find data/minio/warehouse -name "*.parquet"

# Check disk usage
du -sh data/minio/
```

### Backup Data

```bash
# Create backup directory
mkdir -p backups

# Backup all data (recommended)
tar czf backups/all-data-$(date +%Y%m%d-%H%M%S).tar.gz data/

# Or backup individually:

# MinIO (Iceberg tables)
tar czf backups/minio-$(date +%Y%m%d-%H%M%S).tar.gz data/minio/

# PostgreSQL (Hive Metastore)
tar czf backups/postgres-$(date +%Y%m%d-%H%M%S).tar.gz data/postgres/

# Kafka (message logs)
tar czf backups/kafka-$(date +%Y%m%d-%H%M%S).tar.gz data/kafka/

# Zookeeper (coordination data)
tar czf backups/zookeeper-$(date +%Y%m%d-%H%M%S).tar.gz data/zookeeper/
```

### Restore Data

```bash
# Stop services first
docker-compose down

# Restore all data
rm -rf data/
tar xzf backups/all-data-20241125-120000.tar.gz

# Or restore individually:

# Restore MinIO
rm -rf data/minio/
tar xzf backups/minio-20241125-120000.tar.gz

# Restore PostgreSQL
rm -rf data/postgres/
tar xzf backups/postgres-20241125-120000.tar.gz

# Restore Kafka
rm -rf data/kafka/
tar xzf backups/kafka-20241125-120000.tar.gz

# Restore Zookeeper
rm -rf data/zookeeper/
tar xzf backups/zookeeper-20241125-120000.tar.gz

# Restart services
docker-compose up -d
```

### Clean All Data

```bash
# Remove all data (WARNING: Cannot be undone!)
docker-compose down -v
rm -rf data/minio/

# Fresh start
docker-compose up -d
```

### Move to Another Machine

```bash
# On source machine
tar czf structured-logging-data.tar.gz data/

# Transfer file to destination machine
scp structured-logging-data.tar.gz user@destination:/path/to/structured-logging/

# On destination machine
cd /path/to/structured-logging/
tar xzf structured-logging-data.tar.gz
docker-compose up -d
```

## Disk Space Management

### Check Space Usage

```bash
# MinIO data size
du -sh data/minio/

# Per-table size
du -sh data/minio/warehouse/analytics_logs/*/

# Detailed breakdown
du -h data/minio/warehouse/ | sort -h
```

### Clean Old Data

```bash
# Option 1: Use Iceberg retention (automatic)
# Set retentionDays in log config

# Option 2: Manual cleanup via Trino
docker exec -it trino trino --execute "
  DELETE FROM iceberg.analytics_logs.user_events 
  WHERE event_date < CURRENT_DATE - INTERVAL '30' DAY
"

# Option 3: Drop old tables
docker exec -it trino trino --execute "
  DROP TABLE iceberg.analytics_logs.old_table
"
```

## Troubleshooting

### MinIO data disappeared after restart

Check if volume mount is correct:
```bash
docker inspect minio | grep -A 5 "Mounts"
# Should show: ./data/minio:/data
```

### Permission issues

Fix ownership:
```bash
sudo chown -R $USER:$USER data/minio/
chmod -R 755 data/minio/
```

### Corrupted data

Restore from backup:
```bash
docker-compose down
rm -rf data/minio/
cp -r backups/minio-latest/ data/minio/
docker-compose up -d
```

### Check data integrity

```bash
# Query tables to verify
docker exec -it trino trino --execute "
  SELECT COUNT(*) FROM iceberg.analytics_logs.user_events
"

# Check Iceberg metadata
docker exec minio mc ls myminio/warehouse/analytics_logs/user_events/metadata/
```

## Best Practices

1. **Regular Backups**:
   ```bash
   # Add to crontab
   0 2 * * * cd /path/to/structured-logging && tar czf backups/minio-$(date +\%Y\%m\%d).tar.gz data/minio/
   ```

2. **Monitor Disk Space**:
   ```bash
   df -h data/minio/
   ```

3. **Test Restores**:
   ```bash
   # Periodically test backup restoration
   ```

4. **Version Control**:
   - Keep `data/` in `.gitignore`
   - Never commit actual data
   - Document schema changes

5. **Development vs Production**:
   - Dev: Use local host directory
   - Prod: Use mounted network storage (NFS, EFS, etc.)

## Production Considerations

For production deployments, consider:

1. **Network Storage**: Mount shared storage (NFS, AWS EFS)
   ```yaml
   volumes:
     - /mnt/shared-storage/minio:/data
   ```

2. **Managed S3**: Use real S3 instead of MinIO
   ```yaml
   # Remove MinIO, use AWS S3 directly
   ```

3. **Monitoring**: Set up disk space alerts

4. **Replication**: Use S3 cross-region replication

5. **Lifecycle Policies**: Auto-archive old data

## Summary

- ✅ **All persistent data** stored on host at `./data/`
- ✅ **MinIO**: `./data/minio/` (Iceberg tables, Parquet files)
- ✅ **PostgreSQL**: `./data/postgres/` (Hive Metastore)
- ✅ **Kafka**: `./data/kafka/` (message logs, offsets)
- ✅ **Zookeeper**: `./data/zookeeper/` (coordination data)
- ✅ Survives `docker-compose down -v`
- ✅ Single backup command: `tar czf backup.tar.gz data/`
- ✅ Portable across machines
- ✅ No Docker volumes used
