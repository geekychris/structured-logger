# Adding New Loggers - Step-by-Step Guide

This guide explains how to create a new logger type, how the configuration flows through the system, and how the Spark consumer automatically discovers new log types.

## Table of Contents
1. [Understanding the Configuration Flow](#understanding-the-configuration-flow)
2. [Step-by-Step: Adding a New Logger](#step-by-step-adding-a-new-logger)
3. [How the Spark Consumer Discovers Configs](#how-the-spark-consumer-discovers-configs)
4. [Testing Your New Logger](#testing-your-new-logger)
5. [Troubleshooting](#troubleshooting)

> **📊 Visual Learner?** Check out [docs/CONFIG_FLOW_DIAGRAM.md](docs/CONFIG_FLOW_DIAGRAM.md) for ASCII diagrams showing the complete flow!

---

## Understanding the Configuration Flow

The system uses a **shared directory approach** where all log configurations live in one place and are accessible to both the code generator and the Spark consumer.

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────────┐
│  Host Machine: log-configs/                                  │
│  ├── api_metrics.json        ← Your log configurations       │
│  ├── user_events.json                                        │
│  └── my_new_log.json         ← New config you create         │
└──────────────┬────────────────────────────────┬──────────────┘
               │                                │
               │ Read by                        │ Docker Volume Mount
               │ Generator                      │ (./log-configs:/opt/spark-apps/log-configs)
               │                                │
               ↓                                ↓
    ┌──────────────────────┐        ┌──────────────────────────┐
    │  Code Generator      │        │  Spark Container         │
    │  (Python script)     │        │  /opt/spark-apps/        │
    │                      │        │    log-configs/          │
    │  Generates:          │        │                          │
    │  • Java loggers      │        │  Spark Consumer reads    │
    │  • Python loggers    │        │  ALL .json files and:    │
    └──────────────────────┘        │  • Subscribes to topics  │
                                    │  • Creates Iceberg tables│
                                    │  • Streams data          │
                                    └──────────────────────────┘
```

### Key Points

✅ **Single Source of Truth**: `log-configs/` directory contains all log schemas  
✅ **No Manual Copying**: Docker volume mount makes configs available inside containers  
✅ **Automatic Discovery**: Spark consumer loads ALL `.json` files from the directory  
✅ **Hot Restart**: Just restart the consumer to pick up new/changed configs

---

## Step-by-Step: Adding a New Logger

### Step 1: Create Your Log Configuration

Create a new JSON file in the `log-configs/` directory. Follow the schema defined in `config-schema/log-config-schema.json`.

**Example: `log-configs/database_metrics.json`**

```json
{
  "name": "DatabaseMetrics",
  "version": "1.0.0",
  "description": "Database query performance and connection metrics",
  "kafka": {
    "topic": "database-metrics",
    "partitions": 4,
    "replication_factor": 1,
    "retention_ms": 604800000
  },
  "warehouse": {
    "table_name": "analytics_logs.database_metrics",
    "partition_by": ["metric_date", "database_name"],
    "sort_by": ["timestamp", "query_type"],
    "retention_days": 30
  },
  "fields": [
    {
      "name": "timestamp",
      "type": "timestamp",
      "required": true,
      "description": "Metric timestamp"
    },
    {
      "name": "metric_date",
      "type": "date",
      "required": true,
      "description": "Date for partitioning"
    },
    {
      "name": "database_name",
      "type": "string",
      "required": true,
      "description": "Name of the database"
    },
    {
      "name": "query_type",
      "type": "string",
      "required": true,
      "description": "Type of query (SELECT, INSERT, UPDATE, DELETE)"
    },
    {
      "name": "execution_time_ms",
      "type": "long",
      "required": true,
      "description": "Query execution time in milliseconds"
    },
    {
      "name": "rows_affected",
      "type": "long",
      "required": false,
      "description": "Number of rows affected"
    },
    {
      "name": "connection_pool_size",
      "type": "int",
      "required": false,
      "description": "Current connection pool size"
    },
    {
      "name": "error_message",
      "type": "string",
      "required": false,
      "description": "Error message if query failed"
    }
  ]
}
```

**Configuration Tips:**

- **name**: Use PascalCase (e.g., `DatabaseMetrics`, `UserEvents`)
- **version**: Follow semantic versioning (e.g., `1.0.0`)
- **kafka.topic**: Use kebab-case (e.g., `database-metrics`, `user-events`)
- **warehouse.table_name**: Use database.schema format (e.g., `analytics_logs.database_metrics`)
- **partition_by**: Choose fields that:
  - Have moderate cardinality (not too many unique values)
  - Are commonly used in WHERE clauses
  - Example: date fields + categorical fields
- **sort_by**: Choose fields commonly used in filters for better query performance
- **fields**: Use snake_case for field names

### Step 2: Validate Your Configuration (Optional)

You can validate your configuration against the JSON schema:

```bash
# Install jsonschema validator if needed
pip install jsonschema

# Validate your config
python -c "
import json
import jsonschema

with open('config-schema/log-config-schema.json') as schema_file:
    schema = json.load(schema_file)

with open('log-configs/database_metrics.json') as config_file:
    config = json.load(config_file)

jsonschema.validate(config, schema)
print('✅ Configuration is valid!')
"
```

### Step 3: Generate Type-Safe Logger Code

Run the code generator to create Java and Python logger classes:

```bash
# Generate both Java and Python loggers
python generators/generate_loggers.py log-configs/database_metrics.json

# Or specify which language(s) to generate
python generators/generate_loggers.py log-configs/database_metrics.json --lang java
python generators/generate_loggers.py log-configs/database_metrics.json --lang python
```

**Generated Files:**

- **Java**: `java-logger/src/main/java/com/logging/generated/DatabaseMetricsLogger.java`
- **Python**: `python-logger/structured_logging/generated/databasemetrics_logger.py`

The generated code is:
- ✅ Type-safe (compile-time checking)
- ✅ Immutable (marked as "DO NOT EDIT")
- ✅ Includes builder patterns
- ✅ Has proper JSON serialization
- ✅ Embeds version metadata

### Step 4: Build Logger Libraries (If Using in Applications)

If you're using the generated loggers in your applications, rebuild the logger libraries:

**Java:**
```bash
cd java-logger
mvn clean package
# Use the JAR in your application's dependencies
```

**Python:**
```bash
cd python-logger
pip install -e .  # Install in development mode
# Or: pip install .
```

### Step 5: Restart Spark Consumer to Pick Up New Config

This is the crucial step where the Spark consumer discovers your new configuration.

```bash
# Stop the current consumer
docker exec spark-master pkill -f StructuredLogConsumer

# Start the consumer (it will automatically discover all configs in log-configs/)
./start-consumer.sh
```

**What happens when the consumer starts:**

1. Spark container has access to `log-configs/` via Docker volume mount
2. Consumer's `loadConfigs()` method reads ALL `.json` files from the directory:
   ```java
   // From StructuredLogConsumer.java
   private static List<LogConfig> loadConfigs(String configPath) {
       Path path = Paths.get(configPath);
       if (Files.isDirectory(path)) {
           // Load all JSON files from directory
           return paths.filter(p -> p.toString().endsWith(".json"))
                       .map(this::loadConfig)
                       .collect(Collectors.toList());
       }
   }
   ```
3. For each config, the consumer:
   - Subscribes to the Kafka topic
   - Creates the Iceberg table (if it doesn't exist)
   - Starts a streaming query to write data

### Step 6: Verify Consumer Picked Up Your Config

Check the consumer logs to confirm your new logger was loaded:

```bash
docker exec spark-master tail -50 /opt/spark-data/consumer.log
```

**Expected output:**
```
Loaded 3 log configurations
Processing log config: ApiMetrics -> analytics_logs.api_metrics
✓ Table ready: local.analytics_logs.api_metrics
Started streaming query for ApiMetrics

Processing log config: UserEvents -> analytics_logs.user_events
✓ Table ready: local.analytics_logs.user_events
Started streaming query for UserEvents

Processing log config: DatabaseMetrics -> analytics_logs.database_metrics
Creating new Iceberg table: local.analytics_logs.database_metrics
✓ Created table: local.analytics_logs.database_metrics
Started streaming query for DatabaseMetrics

Started 3 streaming queries
```

---

## How the Spark Consumer Discovers Configs

### Docker Volume Mount (The Secret Sauce)

The `docker-compose.yml` file contains this critical configuration:

```yaml
volumes:
  - ./log-configs:/opt/spark-apps/log-configs
```

This means:
- **Host path**: `./log-configs/` (your local directory)
- **Container path**: `/opt/spark-apps/log-configs/` (inside Spark container)
- **Synchronization**: Any file you create/modify in `log-configs/` is immediately visible inside the container

### Consumer Startup Process

When you run `./start-consumer.sh`, it executes:

```bash
spark-submit \
  --class com.logging.consumer.StructuredLogConsumer \
  /opt/spark-apps-java/structured-log-consumer-1.0.0.jar \
  /opt/spark-apps/log-configs/ \    # ← This directory path
  kafka:29092 \
  /opt/spark-data/warehouse
```

The consumer receives `/opt/spark-apps/log-configs/` as the first argument and:

1. **Lists all JSON files** in the directory
2. **Parses each file** into a `LogConfig` object
3. **Creates a streaming query** for each config that:
   - Reads from the Kafka topic specified in the config
   - Parses JSON using the schema from the config
   - Writes to the Iceberg table specified in the config

### Why This Design Works

✅ **No code changes**: Add configs without modifying Java code  
✅ **No copying**: Volume mount keeps configs in sync  
✅ **Multi-tenant**: One consumer handles all log types  
✅ **Dynamic**: Restart consumer to pick up new configs  
✅ **Version controlled**: All configs in one Git repository

---

## Testing Your New Logger

### Step 1: Use the Generated Logger in Your Code

**Java Example:**

```java
import com.logging.generated.DatabaseMetricsLogger;
import java.time.Instant;
import java.time.LocalDate;

public class DatabaseMonitor {
    public static void main(String[] args) {
        // Create logger (connects to Kafka)
        try (DatabaseMetricsLogger logger = new DatabaseMetricsLogger()) {
            
            // Log a successful query
            logger.log(
                Instant.now(),                    // timestamp
                LocalDate.now(),                  // metric_date
                "orders_db",                      // database_name
                "SELECT",                         // query_type
                45L,                              // execution_time_ms
                100L,                             // rows_affected
                25,                               // connection_pool_size
                null                              // error_message
            );
            
            // Log a slow query
            logger.log(
                Instant.now(),
                LocalDate.now(),
                "analytics_db",
                "SELECT",
                5432L,                            // Slow query!
                1000000L,
                50,
                null
            );
            
        } // Auto-closes and flushes to Kafka
    }
}
```

**Python Example:**

```python
from structured_logging.generated.databasemetrics_logger import DatabaseMetricsLogger
from datetime import datetime, date

def monitor_query(db_name, query_type, execution_time):
    with DatabaseMetricsLogger() as logger:
        logger.log(
            timestamp=datetime.utcnow(),
            metric_date=date.today(),
            database_name=db_name,
            query_type=query_type,
            execution_time_ms=execution_time,
            rows_affected=100,
            connection_pool_size=25
        )

# Use it
monitor_query("orders_db", "SELECT", 45)
monitor_query("analytics_db", "INSERT", 234)
```

### Step 2: Verify Data in Kafka (Optional)

Check that your logs are being published to Kafka:

```bash
# List topics - you should see your new topic
docker exec kafka kafka-topics --list --bootstrap-server localhost:9092

# Consume messages from your topic
docker exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic database-metrics \
  --from-beginning \
  --max-messages 5
```

### Step 3: Wait for Spark to Process (10 seconds)

The Spark consumer processes data in micro-batches every 10 seconds. Wait at least 10-15 seconds after sending logs.

### Step 4: Query Data with Trino

```bash
# Connect to Trino
docker exec -it trino trino

# Verify table exists
SHOW TABLES IN local.analytics_logs;

# Query your data
SELECT * FROM local.analytics_logs.database_metrics 
ORDER BY timestamp DESC 
LIMIT 10;

# Check counts
SELECT 
    database_name,
    query_type,
    COUNT(*) as query_count,
    AVG(execution_time_ms) as avg_time_ms,
    MAX(execution_time_ms) as max_time_ms
FROM local.analytics_logs.database_metrics
WHERE metric_date = CURRENT_DATE
GROUP BY database_name, query_type
ORDER BY query_count DESC;
```

### Step 5: Verify Table Structure

Check that partitioning and schema are correct:

```sql
-- View table metadata
DESCRIBE local.analytics_logs.database_metrics;

-- Check partitions
SELECT * FROM local.analytics_logs.database_metrics.partitions;

-- Check data files
SELECT * FROM local.analytics_logs.database_metrics.files;

-- View Iceberg snapshots
SELECT * FROM local.analytics_logs.database_metrics.snapshots;
```

---

## Troubleshooting

### Problem: Consumer doesn't see my new config

**Symptoms:** Consumer logs show old config count (e.g., "Loaded 2 log configurations" instead of 3)

**Solutions:**

1. **Verify file is in the right location:**
   ```bash
   ls -la log-configs/
   # Should show your new .json file
   ```

2. **Verify file has .json extension:**
   ```bash
   # Consumer only loads files ending in .json
   mv log-configs/myconfig.txt log-configs/myconfig.json
   ```

3. **Check file is valid JSON:**
   ```bash
   python -m json.tool log-configs/database_metrics.json
   ```

4. **Verify volume mount is working:**
   ```bash
   # Check file is visible inside container
   docker exec spark-master ls -la /opt/spark-apps/log-configs/
   ```

5. **Restart consumer:**
   ```bash
   docker exec spark-master pkill -f StructuredLogConsumer
   ./start-consumer.sh
   ```

### Problem: Generated logger doesn't compile

**Symptoms:** Java compilation errors or Python import errors

**Solutions:**

1. **Verify generator ran successfully:**
   ```bash
   python generators/generate_loggers.py log-configs/database_metrics.json
   # Should output: "Generated Java logger: ..." and "Generated Python logger: ..."
   ```

2. **Check generated files exist:**
   ```bash
   ls -la java-logger/src/main/java/com/logging/generated/
   ls -la python-logger/structured_logging/generated/
   ```

3. **Rebuild logger libraries:**
   ```bash
   cd java-logger && mvn clean package
   cd python-logger && pip install -e .
   ```

### Problem: No data appearing in Iceberg table

**Symptoms:** Table exists but queries return 0 rows

**Solutions:**

1. **Check consumer is running:**
   ```bash
   docker exec spark-master ps aux | grep StructuredLogConsumer
   ```

2. **Check consumer logs for errors:**
   ```bash
   docker exec spark-master tail -100 /opt/spark-data/consumer.log | grep -i error
   ```

3. **Verify Kafka topic has messages:**
   ```bash
   docker exec kafka kafka-console-consumer \
     --bootstrap-server localhost:9092 \
     --topic database-metrics \
     --from-beginning \
     --max-messages 1
   ```

4. **Check Spark consumer lag:**
   ```bash
   docker exec kafka kafka-consumer-groups \
     --bootstrap-server localhost:9092 \
     --describe \
     --all-groups
   ```

5. **Wait for micro-batch (10+ seconds):**
   - Spark processes in 10-second intervals
   - Data isn't instant, wait at least 15 seconds

6. **Check application is publishing:**
   - Add logging to your application
   - Verify Kafka connection (check `KAFKA_BOOTSTRAP_SERVERS` env var)

### Problem: Schema mismatch errors

**Symptoms:** Consumer logs show JSON parsing errors

**Solutions:**

1. **Verify field names match:**
   - Config uses snake_case: `execution_time_ms`
   - Logger uses camelCase in Java but serializes to snake_case
   - Python uses snake_case directly

2. **Check required fields:**
   - All fields marked `required: true` must be provided
   - Check for null values in required fields

3. **Verify field types:**
   - Config says `long` but you're sending a string
   - Use appropriate Java types: `Long` for long, `Instant` for timestamp

### Problem: Table creation fails

**Symptoms:** Consumer logs show "Failed to manage table" errors

**Solutions:**

1. **Check Hive Metastore is running:**
   ```bash
   docker ps | grep hive-metastore
   docker exec hive-metastore pgrep -f HiveMetaStore
   ```

2. **Verify database exists:**
   ```sql
   -- In Trino
   SHOW SCHEMAS IN local;
   CREATE SCHEMA IF NOT EXISTS local.analytics_logs;
   ```

3. **Check MinIO/storage access:**
   ```bash
   # Verify MinIO is accessible
   docker exec spark-master curl http://minio:9000
   ```

4. **Check permissions:**
   - MinIO credentials: admin/password123
   - Warehouse path: `/opt/spark-data/warehouse` or `s3a://warehouse/`

---

## Summary

To add a new logger type:

1. ✅ **Create** `log-configs/my_new_log.json` with your schema
2. ✅ **Generate** loggers: `python generators/generate_loggers.py log-configs/my_new_log.json`
3. ✅ **Restart** Spark consumer: `./start-consumer.sh`
4. ✅ **Use** generated logger in your application
5. ✅ **Query** data in Trino after ~15 seconds

The Spark consumer **automatically discovers** new configs because:
- Docker volume mounts `log-configs/` into the container
- Consumer loads ALL `.json` files from that directory
- No manual copying or code changes needed!

**Related Documentation:**
- [ARCHITECTURE.md](ARCHITECTURE.md) - System architecture and data flow
- [SCHEMA_EVOLUTION.md](SCHEMA_EVOLUTION.md) - Updating existing schemas
- [BUILD_AND_RUN.md](BUILD_AND_RUN.md) - Building and deployment
- [QUICK_REFERENCE.md](QUICK_REFERENCE.md) - Common operations
