# Migration to Standalone Project

## Summary

The Structured Logging System has been made fully standalone and can now be moved out of the `trino_docker` parent project.

## What Was Done

### 1. Created Self-Contained Docker Compose

Created `docker-compose.yml` with all required services:
- MinIO (S3-compatible storage)
- PostgreSQL (Hive metastore database)
- Hive Metastore (Iceberg catalog)
- Spark Master & Worker
- Zookeeper & Kafka
- Trino (SQL query engine)

**Changes from parent**:
- Container names: Same as parent (no prefix needed)
- Network renamed to `logging-network` 
- Volume mounts point to local directories within the project
- Same ports (won't conflict since not running concurrently)

### 2. Copied Required Docker Configuration Files

Created `docker/` directory with:
- `Dockerfile.hive-metastore` - Hive metastore container build
- `hive-conf/` - Hive configuration files
- `trino-config/` - Trino server configuration
- `trino-catalog/` - Trino catalog definitions (Iceberg)

### 3. Updated Scripts

- Created `start-consumer.sh` - Simplified startup script
- Uses standard container names (`spark-master`, `minio`, etc.)
- Same commands work in both projects

### 4. Volume Mount Changes

**Old (parent project)**:
```yaml
volumes:
  - ../spark-apps:/opt/spark-apps
  - ../spark-apps-java:/opt/spark-apps-java
```

**New (standalone)**:
```yaml
volumes:
  - ./log-configs:/opt/spark-apps/log-configs
  - ./spark-consumer/target:/opt/spark-apps-java
  - ./data:/opt/spark-data
```

### 5. Documentation

Created:
- **STANDALONE_SETUP.md** - Complete standalone setup guide
- **MIGRATION_NOTES.md** - This file
- **.gitignore** - Proper gitignore for standalone project

Updated:
- Volume mount paths in docker-compose
- Script references to use standard names

## Moving the Project

### Step 1: Stop Current Services

If the parent `trino_docker` project is running:

```bash
cd /Users/chris/code/warp_experiments/trino_docker
docker-compose down
```

### Step 2: Move the Directory

```bash
cd /Users/chris/code/warp_experiments
mv trino_docker/structured-logging ./structured-logging
```

### Step 3: Start Standalone Project

```bash
cd structured-logging

# Build consumer
cd spark-consumer
mvn clean package -DskipTests
cd ..

# Start all services
docker-compose up -d

# Wait for services to start (1-2 minutes)
docker-compose ps

# Start consumer
./start-consumer.sh
```

### Step 4: Verify

```bash
# Check tables visible in Trino
docker exec -it trino trino \
  --execute "SHOW TABLES IN iceberg.analytics_logs"

# Send test data
cd examples
python3 python_example.py

# Query data
docker exec -it trino trino \
  --execute "SELECT COUNT(*) FROM iceberg.analytics_logs.user_events"
```

## No Dependencies on Parent

The project is now completely independent:

✅ All Docker images and services defined locally
✅ All configuration files copied locally
✅ All volume mounts use local paths
✅ All scripts reference local containers
✅ All documentation updated

## Ports and Names

The project uses the **exact same** ports and container names as the parent:
- **9000, 9001**: MinIO → `minio`
- **9092**: Kafka → `kafka`
- **8081**: Trino → `trino`
- **8082**: Spark Master UI → `spark-master`
- **5432**: PostgreSQL → `postgres`
- **9083**: Hive Metastore → `hive-metastore`
- **2181**: Zookeeper → `zookeeper`
- **7077**: Spark Master → `spark-master`

**Note**: Since you won't run both projects concurrently, they can share ports and container names. This makes commands simpler and more portable.

## Data Migration (Optional)

If you want to keep existing data from the parent project:

### Option 1: Copy MinIO Data

```bash
# Export from parent project
cd /Users/chris/code/warp_experiments/trino_docker
docker run --rm \
  -v trino_docker_minio-data:/data \
  -v $(pwd)/backup:/backup \
  alpine tar czf /backup/minio-export.tar.gz /data

# Import to new project
cd /Users/chris/code/warp_experiments/structured-logging
docker-compose up -d minio
docker run --rm \
  -v structured_logging_minio-data:/data \
  -v $(pwd)/backup:/backup \
  alpine tar xzf /backup/minio-export.tar.gz -C /
```

### Option 2: Start Fresh

Just start the new project - the consumer will create new tables and data.

## What to Clean Up

After migration, you can:

1. **Remove old log configs from parent** (if no longer needed):
   ```bash
   rm -rf /Users/chris/code/warp_experiments/trino_docker/spark-apps/log-configs/*.json
   ```

2. **Remove old JAR from parent** (if no longer needed):
   ```bash
   rm /Users/chris/code/warp_experiments/trino_docker/spark-apps-java/structured-log-consumer-*.jar
   ```

3. **Or just leave it** - they don't interfere with the standalone project

## Testing Checklist

After migration, verify:

- [ ] `docker-compose up -d` starts all services
- [ ] `docker-compose ps` shows all services healthy
- [ ] `./start-consumer.sh` starts consumer without errors
- [ ] Trino can see `analytics_logs` schema
- [ ] Trino can see `user_events` and `api_metrics` tables
- [ ] Python example runs and sends data
- [ ] Data appears in Trino queries
- [ ] Data appears in MinIO at `myminio/warehouse/`

## Rollback

If you need to go back to the parent project:

```bash
# Stop standalone
cd structured-logging
docker-compose down

# Move back
cd ..
mv structured-logging trino_docker/

# Start parent
cd trino_docker
docker-compose up -d
```

## Future Considerations

The project is now portable and can be:
- Moved to any directory
- Pushed to a separate Git repository
- Deployed independently
- Run on different machines
- Shared as a standalone package

No modifications needed!
