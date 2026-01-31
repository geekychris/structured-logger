# Structured Logging - Quick Start Guide

This guide gets you up and running with the envelope-wrapped structured logging system in 5 minutes.

## What You Get

✅ **Type-safe loggers** auto-generated from JSON configs  
✅ **Envelope format** with `_log_type`, `_version`, `_log_class` metadata  
✅ **End-to-end pipeline**: Kafka → Spark → Iceberg  
✅ **Multi-language**: Java and Python support  
✅ **Reusable**: Use the generator in any project  

## Architecture Overview

```
Your App → Logger → Kafka → Spark Consumer → Iceberg Tables
   ↓          ↓        ↓           ↓              ↓
  Java/   Envelope  Topics    Parses &      Analytics
 Python    Wrapper           Extracts       Warehouse
```

**Message Format in Kafka:**
```json
{
  "_log_type": "user_events",
  "_log_class": "UserEvents", 
  "_version": "1.0.0",
  "data": {
    "user_id": "123",
    "event_type": "click",
    ...
  }
}
```

## Quick Start

### 1. Generate Loggers

```bash
# Generate logger from config
python3 generators/generate_loggers.py log-configs/user_events.json

# This creates:
# - java-logger/src/main/java/com/logging/generated/UserEventsLogger.java
# - python-logger/structured_logging/generated/userevents_logger.py  
# - scripts/create-topic-user-events.sh
```

### 2. Use in Your Code

**Java:**
```java
UserEventsLogger logger = new UserEventsLogger();

logger.log(
    Instant.now(),
    LocalDate.now(),
    "user_123",
    "session_abc",
    "click",
    "/products/laptop",
    Map.of("source", "web"),
    "desktop",
    150L
);

logger.close();
```

**Python:**
```python
from structured_logging.generated import UserEventsLogger

logger = UserEventsLogger()
logger.log(
    timestamp=datetime.now(),
    event_date=date.today(),
    user_id="user_123",
    ...
)
logger.close()
```

### 3. Verify It Works

```bash
# Run the demo
./run-java-demo.sh

# Verify envelope format
./verify_envelope.sh

# Check data in Iceberg
docker exec trino trino --execute \
  "SELECT COUNT(*) FROM iceberg.analytics_logs.user_events"
```

## For Other Projects

### Option 1: Call Generator Directly

```bash
# From your project
python3 /path/to/structured-logging/generators/generate_loggers.py \
  your-config.json \
  --java-output ./src/main/java/logging \
  --python-output ./logging/generated
```

### Option 2: Install as Package

```bash
# From structured-logging directory
cd generators && pip install -e .

# Now use from anywhere
structured-logger-generate your-config.json --lang java
```

### Option 3: Copy the Script

```bash
# Standalone script, no dependencies
cp generators/generate_loggers.py your-project/tools/
python3 your-project/tools/generate_loggers.py your-config.json
```

## Configuration File

Create `your-config.json`:

```json
{
  "name": "YourLogger",
  "log_type": "your_type",
  "version": "1.0.0",
  "description": "What this logger tracks",
  "kafka": {
    "topic": "your-topic",
    "partitions": 6,
    "replication_factor": 3
  },
  "warehouse": {
    "table_name": "analytics_logs.your_table"
  },
  "fields": [
    {
      "name": "timestamp",
      "type": "timestamp",
      "required": true,
      "description": "When the event occurred"
    },
    {
      "name": "user_id",
      "type": "string",
      "required": true
    }
  ]
}
```

**Supported types:** `string`, `int`, `long`, `float`, `double`, `boolean`, `timestamp`, `date`, `array<string>`, `map<string,string>`

## Consumer Setup

Your Spark/Flink consumer must parse the envelope:

```python
from pyspark.sql.types import StructType, StructField, StringType
from pyspark.sql.functions import from_json, col

# Define envelope schema
envelope_schema = StructType([
    StructField("_log_type", StringType(), False),
    StructField("_log_class", StringType(), False),
    StructField("_version", StringType(), False),
    StructField("data", your_business_schema, False)
])

# Parse and extract data
df = kafka_df.select(
    from_json(col("value").cast("string"), envelope_schema).alias("envelope")
).select("envelope.data.*")
```

**Reference:** See `spark-consumer/kafka-to-iceberg-consumer.py` for complete implementation.

## Testing

```bash
# Run integration tests
python3 tests/test_envelope_integration.py

# Quick verification
./verify_envelope.sh
```

## Reset and Rebuild

**Interactive mode** (prompts for confirmation):
```bash
./reset-and-rebuild.sh
```

**Automatic mode** (no prompts):
```bash
# Basic reset (preserves Kafka data)
./reset-and-rebuild-auto.sh

# Full reset with Kafka cleanup
./reset-and-rebuild-auto.sh --clear-kafka

# Full reset with code regeneration
./reset-and-rebuild-auto.sh --clear-kafka --regenerate
```

**What it does:**
1. Drops all Iceberg tables
2. Stops Spark consumer
3. Optionally clears Kafka topics
4. Optionally regenerates logger code
5. Rebuilds Java logger
6. Updates and restarts consumer
7. Sends test data
8. Verifies envelope format

## Files Generated

For config `user_events.json`:

| File | Purpose |
|------|---------|
| `UserEventsLogger.java` | Java logger with envelope wrapper |
| `userevents_logger.py` | Python logger with envelope wrapper |
| `create-topic-user-events.sh` | Kafka topic creation script |

## Troubleshooting

### "Envelope fields missing in Kafka"

**Check 1:** Rebuild logger library
```bash
cd java-logger && mvn clean package
```

**Check 2:** Verify BaseStructuredLogger has envelope code
```bash
grep -A 5 "envelope.put" java-logger/src/main/java/com/logging/BaseStructuredLogger.java
```

### "Consumer can't parse messages"

**Check 1:** Consumer has envelope schema
```python
StructField("_log_type", StringType(), False),
StructField("data", business_schema, False)
```

**Check 2:** Extract data field correctly
```python
.select("envelope.data.*")  # Correct
.select("envelope.*")        # Wrong - includes metadata
```

### "Data not reaching Iceberg"

**Check 1:** Consumer is running
```bash
docker exec spark-master ps aux | grep kafka-to-iceberg
```

**Check 2:** No errors in logs
```bash
docker exec spark-master tail -50 /opt/spark-data/consumer.log
```

**Check 3:** Messages in Kafka
```bash
docker exec kafka kafka-run-class kafka.tools.GetOffsetShell \
  --broker-list localhost:9092 --topic user-events --time -1
```

## Next Steps

1. **Read full documentation:**
   - `generators/README.md` - Complete generator docs
   - `ENVELOPE_FORMAT.md` - Envelope format spec
   - `spark-consumer/SHARED_TOPIC_IMPLEMENTATION.md` - Advanced consumer patterns

2. **Add your own log configs:**
   - Create JSON in `log-configs/`
   - Run generator
   - Use in your code

3. **Set up CI/CD:**
   - See `generators/README.md` for examples
   - Auto-generate on config changes
   - Test envelope format in pipeline

## Key Files

| File | Description |
|------|-------------|
| `generators/generate_loggers.py` | Logger code generator (standalone) |
| `generators/README.md` | Full generator documentation |
| `generators/setup.py` | Install as Python package |
| `ENVELOPE_FORMAT.md` | Envelope format specification |
| `verify_envelope.sh` | Quick envelope verification |
| `tests/test_envelope_integration.py` | Integration tests |
| `spark-consumer/kafka-to-iceberg-consumer.py` | Reference consumer implementation |

## Support

**Verify setup works:**
```bash
./verify_envelope.sh
```

**Check message format:**
```bash
docker exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic user-events \
  --max-messages 1
```

**Common issues:** See Troubleshooting section in `generators/README.md`
