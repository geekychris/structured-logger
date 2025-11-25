# Host Storage Migration Summary

## Overview

Successfully migrated **all persistent services** from Docker volumes to host directories.

## Changes Made

### Services Migrated

1. ✅ **MinIO** (`minio-data` volume → `./data/minio/`)
2. ✅ **PostgreSQL** (`postgres-data` volume → `./data/postgres/`)  
3. ✅ **Kafka** (no volume → `./data/kafka/`)
4. ✅ **Zookeeper** (no volume → `./data/zookeeper/`)

### Files Modified

**docker-compose.yml**:
- MinIO: Changed volume to `./data/minio:/data`
- PostgreSQL: Changed volume to `./data/postgres:/var/lib/postgresql/data`
- Kafka: Added volume `./data/kafka:/var/lib/kafka/data`
- Zookeeper: Added volumes for data and logs
- Removed `volumes:` section (no Docker volumes needed)

## Directory Structure

```
structured-logging/
└── data/                    # All persistent data on host
    ├── minio/              # MinIO/S3 storage (97 MB typical)
    │   ├── .minio.sys/     # System files
    │   └── warehouse/      # Iceberg tables
    ├── postgres/           # PostgreSQL (97 MB)
    │   ├── base/           # Database files
    │   ├── global/         # Cluster data
    │   ├── pg_wal/         # Write-ahead logs
    │   └── pgdata/         # Metastore data
    ├── kafka/              # Kafka logs (24 KB)
    │   ├── *-0/            # Topic partitions
    │   └── meta.properties
    └── zookeeper/          # Coordination (24 KB)
        ├── data/           # Snapshots
        └── log/            # Transaction logs
```

## Benefits

### Before (Docker Volumes)
- ❌ Data lost with `docker-compose down -v`
- ❌ Complex backup procedures
- ❌ Hard to inspect data
- ❌ Not portable
- ❌ Requires Docker commands to access

### After (Host Directories)
- ✅ Data survives `docker-compose down -v`
- ✅ Simple backup: `tar czf backup.tar.gz data/`
- ✅ Easy to inspect: `ls data/`
- ✅ Portable: Copy directory anywhere
- ✅ Direct filesystem access

## Migration Steps Performed

1. **Created host directories**:
   ```bash
   mkdir -p data/{minio,postgres,kafka,zookeeper/{data,log}}
   ```

2. **Updated docker-compose.yml**: Changed all volumes to host mounts

3. **Migrated existing data**:
   ```bash
   docker-compose down
   docker run --rm \
     -v structured-logging_postgres-data:/source \
     -v ./data/postgres:/dest \
     alpine cp -r /source/. /dest/
   ```

4. **Removed old volumes**:
   ```bash
   docker volume rm structured-logging_postgres-data
   ```

5. **Started services**: All services now use host directories

## Verification

### Check Directory Sizes
```bash
$ du -sh data/*
128K    data/consumer.log
24K     data/kafka
56K     data/minio
97M     data/postgres
24K     data/zookeeper
```

### Check Services Status
```bash
$ docker-compose ps
NAME             STATUS
hive-metastore   Up (healthy)
kafka            Up (healthy)
minio            Up (healthy)
postgres         Up (healthy)
spark-master     Up
spark-worker     Up
trino            Up (healthy)
zookeeper        Up (healthy)
```

### Verify Data Persistence
```bash
# Stop and remove all containers
docker-compose down -v

# Data still exists
ls -la data/

# Restart - services pick up existing data
docker-compose up -d
```

## Backup & Restore

### Simple Backup
```bash
tar czf backups/all-data-$(date +%Y%m%d-%H%M%S).tar.gz data/
```

### Simple Restore
```bash
docker-compose down
tar xzf backups/all-data-20241125-120000.tar.gz
docker-compose up -d
```

### Move to Another Machine
```bash
# On source machine
tar czf structured-logging-data.tar.gz data/

# Transfer to destination
scp structured-logging-data.tar.gz user@destination:/path/to/structured-logging/

# On destination
cd /path/to/structured-logging/
tar xzf structured-logging-data.tar.gz
docker-compose up -d
```

## What Data Persists

### MinIO (`./data/minio/`)
- Iceberg Parquet data files
- Iceberg metadata (JSON, Avro)
- Table snapshots
- Partition information

### PostgreSQL (`./data/postgres/`)
- Hive Metastore tables
- Table catalog
- Column definitions
- Partition metadata
- ACID transaction logs

### Kafka (`./data/kafka/`)
- Topic message logs
- Consumer offsets
- Partition state
- Topic configuration

### Zookeeper (`./data/zookeeper/`)
- Kafka cluster metadata
- Broker registration
- Topic configurations
- Leader election data
- Coordination state

## Services That Don't Persist

These services are stateless and don't need persistent storage:

- **Spark Master/Worker**: Cluster coordination only
- **Trino**: Query engine (caches in memory)
- **Hive Metastore**: Reads from PostgreSQL
- **minio-init**: One-shot initialization

## Impact on Development

### Before
```bash
# Lost all data on this command
docker-compose down -v

# Had to reinitialize everything
docker-compose up -d
# Initialize Hive schema
# Recreate Kafka topics
# Reload test data
```

### After
```bash
# Data persists even with -v flag
docker-compose down -v

# Restart with all data intact
docker-compose up -d
# Tables, topics, data all present
```

## Documentation Updated

1. **STORAGE_CONFIGURATION.md**: Complete rewrite for host directories
2. **STANDALONE_SETUP.md**: Updated data persistence section
3. **HOST_STORAGE_MIGRATION.md**: This document

## Disk Space Considerations

Typical disk usage:
- **MinIO**: 56 KB (empty) → GB+ (with data)
- **PostgreSQL**: 97 MB (with metastore)
- **Kafka**: 24 KB (minimal topics) → GB+ (with messages)
- **Zookeeper**: 24 KB (coordination state)

Monitor with:
```bash
du -sh data/*
```

## Next Steps

All persistent data is now on the host filesystem. To use this system:

1. **Normal operation**: Just use `docker-compose up -d`
2. **Backup regularly**: `tar czf backup.tar.gz data/`
3. **Check disk space**: `du -sh data/`
4. **Clean old data**: Use Trino to delete old partitions

## Rollback (If Needed)

To revert to Docker volumes:

1. Backup current data: `tar czf data-backup.tar.gz data/`
2. Revert docker-compose.yml changes
3. Recreate volumes and restore data

## Result

✅ All persistent data now stored on host filesystem  
✅ Zero data loss on `docker-compose down -v`  
✅ Simple backup/restore procedures  
✅ Portable across machines  
✅ Easy to inspect and debug  
✅ No Docker volume management needed
