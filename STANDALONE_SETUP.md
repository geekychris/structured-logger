# Standalone Setup Guide

This guide explains how to run the Structured Logging System as a standalone project.

## Moving from trino_docker Parent Project

The structured-logging project is now self-contained and can be moved to any location.

### Quick Move

```bash
# From the parent directory
cd /Users/chris/code/warp_experiments

# Move the project
mv trino_docker/structured-logging ./structured-logging

# The project is now standalone at:
# /Users/chris/code/warp_experiments/structured-logging
```

## Prerequisites

- Docker and Docker Compose
- Java 11+ (for building)
- Maven 3.6+
- Python 3.8+ (for examples and generators)

## Directory Structure

```
structured-logging/
├── docker/                      # Docker configuration files
│   ├── Dockerfile.hive-metastore
│   ├── hive-conf/              # Hive metastore config
│   ├── trino-config/           # Trino server config  
│   └── trino-catalog/          # Trino catalog definitions
├── docker-compose.yml          # All services definition
├── spark-consumer/             # Spark streaming consumer (Java)
├── java-logger/                # Base Java logger library
├── python-logger/              # Base Python logger library
├── generators/                 # Code generators
├── log-configs/                # Log configuration files
├── examples/                   # Example code
├── data/                       # Local data directory (created on first run)
└── start-consumer.sh           # Consumer startup script
```

## First Time Setup

### 1. Build the Spark Consumer

```bash
cd structured-logging/spark-consumer
mvn clean package -DskipTests
```

This creates `target/structured-log-consumer-1.0.0.jar`.

### 2. Start All Services

From the `structured-logging` directory:

```bash
docker-compose up -d
```

This starts:
- **MinIO**: S3-compatible object storage (ports 9000, 9001)
- **PostgreSQL**: Hive metastore database (port 5432)
- **Hive Metastore**: Iceberg catalog (port 9083)
- **Spark Master**: Spark cluster master (ports 7077, 8082)
- **Spark Worker**: Spark worker node
- **Zookeeper**: Kafka coordination (port 2181)
- **Kafka**: Message streaming (port 9092)
- **Trino**: SQL query engine (port 8081)

### 3. Wait for Services to be Ready

```bash
# Check service health
docker-compose ps

# Wait for all services to show "healthy" or "running"
# This may take 1-2 minutes
```

### 4. Start the Consumer

```bash
./start-consumer.sh
```

### 5. Verify Setup

```bash
# Check consumer is running
docker exec spark-master ps aux | grep StructuredLogConsumer

# View consumer logs
docker exec spark-master tail -f /opt/spark-data/consumer.log

# Check Trino can see tables
docker exec -it trino trino --execute "SHOW SCHEMAS IN iceberg"
docker exec -it trino trino --execute "SHOW TABLES IN iceberg.analytics_logs"
```

## Running Examples

### Python Example

```bash
cd examples
python3 python_example.py
```

### Java Example

```bash
cd examples
javac -cp "../java-logger/target/*:../generated/*" JavaExample.java
java -cp "../java-logger/target/*:../generated/*:." JavaExample
```

### Verify Data

Wait 10-15 seconds for data to be processed, then:

```bash
# Query with Trino
docker exec -it trino trino \
  --execute "SELECT COUNT(*) FROM iceberg.analytics_logs.user_events"

# Check S3/MinIO
docker exec minio mc ls myminio/warehouse/analytics_logs/
```

## Service Endpoints

| Service | Endpoint | Credentials |
|---------|----------|-------------|
| MinIO Console | http://localhost:9001 | admin / password123 |
| MinIO API | localhost:9000 | admin / password123 |
| Trino | localhost:8081 | none |
| Spark Master UI | http://localhost:8082 | none |
| Kafka | localhost:9092 | none |
| Hive Metastore | localhost:9083 | none |

## Stopping Services

```bash
# Stop all services
docker-compose down

# Stop and remove volumes (WARNING: deletes all data)
docker-compose down -v
```

## Container Names

Standard container names (same as parent project):

- `minio`
- `postgres`
- `hive-metastore`
- `spark-master`
- `spark-worker`
- `zookeeper`
- `kafka`
- `trino`

**Note**: Since you won't run both projects concurrently, they can share the same container names and ports.

## Volume Mounts

The docker-compose uses these local directories:

- `./log-configs` → `/opt/spark-apps/log-configs` (in Spark containers)
- `./spark-consumer/target` → `/opt/spark-apps-java` (in Spark containers)
- `./data` → `/opt/spark-data` (in Spark containers)
- `./docker/hive-conf` → `/etc/hadoop/conf` (in Hive metastore)
- `./docker/trino-config` → `/etc/trino` (in Trino)
- `./docker/trino-catalog` → `/etc/trino/catalog` (in Trino)

## Troubleshooting

### Port Conflicts

If you get port conflicts, the parent `trino_docker` project may still be running:

```bash
# Check for conflicting containers
docker ps | grep -E "minio|kafka|spark|trino|hive"

# Stop parent project if running
cd ../trino_docker
docker-compose down
```

### Consumer Not Starting

1. Rebuild the consumer:
   ```bash
   cd spark-consumer
   mvn clean package -DskipTests
   ```

2. Restart:
   ```bash
   docker exec spark-master pkill -f StructuredLogConsumer
   ./start-consumer.sh
   ```

### Trino Can't See Tables

1. Check Hive metastore is healthy:
   ```bash
   docker-compose ps hive-metastore
   ```

2. Verify tables in Hive:
   ```bash
   docker exec -it trino trino \
     --execute "SHOW SCHEMAS IN iceberg"
   ```

### Reset Everything

```bash
# Stop all services and remove volumes
docker-compose down -v

# Remove data directory
rm -rf data/

# Rebuild and restart
docker-compose up -d
cd spark-consumer && mvn clean package -DskipTests && cd ..
./start-consumer.sh
```

## Development Workflow

### Adding New Log Configs

1. Create config file in `log-configs/`:
   ```bash
   vim log-configs/my_new_log.json
   ```

2. Generate loggers:
   ```bash
   python generators/generate_loggers.py \
     --config log-configs/my_new_log.json \
     --java-output generated-loggers/java \
     --python-output generated-loggers/python
   ```

3. Restart consumer to pick up new config:
   ```bash
   docker exec spark-master pkill -f StructuredLogConsumer
   ./start-consumer.sh
   ```

### Modifying Consumer Code

1. Edit code in `spark-consumer/src/`

2. Rebuild:
   ```bash
   cd spark-consumer
   mvn clean package -DskipTests
   ```

3. Restart consumer:
   ```bash
   docker exec spark-master pkill -f StructuredLogConsumer
   cd ..
   ./start-consumer.sh
   ```

## Data Persistence

Data is stored in Docker volumes:
- `structured-logging_minio-data`: S3/MinIO object storage (Parquet files, Iceberg metadata)
- `structured-logging_postgres-data`: Hive metastore database

To backup:
```bash
docker run --rm -v structured_logging_minio-data:/data -v $(pwd)/backup:/backup alpine \
  tar czf /backup/minio-backup.tar.gz /data
```

To restore:
```bash
docker run --rm -v structured_logging_minio-data:/data -v $(pwd)/backup:/backup alpine \
  tar xzf /backup/minio-backup.tar.gz -C /
```

## Production Considerations

For production deployment:

1. **Use external services** instead of Docker Compose:
   - Managed Kafka (Confluent Cloud, AWS MSK)
   - Managed S3 (AWS S3, not MinIO)
   - Managed Hive Metastore (AWS Glue, Databricks Unity Catalog)
   - Kubernetes for Spark/Trino

2. **Update configurations** in `BUILD_AND_RUN.md` for cloud deployment

3. **Set resource limits** in docker-compose for development:
   ```yaml
   services:
     spark-master:
       deploy:
         resources:
           limits:
             cpus: '2'
             memory: 4G
   ```

## Further Documentation

- **[README.md](README.md)**: Project overview
- **[BUILD_AND_RUN.md](BUILD_AND_RUN.md)**: Complete build and usage guide
- **[S3_DEPLOYMENT.md](S3_DEPLOYMENT.md)**: S3/MinIO configuration details
- **[QUICK_REFERENCE.md](QUICK_REFERENCE.md)**: Common commands
- **[SCHEMA_NAMING.md](SCHEMA_NAMING.md)**: Table naming conventions
