# Structured Logger Code Generator

Generate type-safe, envelope-wrapped structured loggers from JSON configuration files.

## Features

- ✅ **Envelope Format**: All messages wrapped with `_log_type`, `_version`, `_log_class` metadata
- ✅ **Type Safety**: Generated code includes full type checking (Java generics, Python type hints)
- ✅ **Multi-Language**: Supports both Java and Python
- ✅ **Builder Pattern**: Fluent builder API for constructing log records
- ✅ **Auto-partitioning**: Automatically routes messages to correct Kafka partitions
- ✅ **Topic Scripts**: Generates Kafka topic creation scripts with correct settings

## Quick Start

### From This Project

```bash
# Generate loggers from a config file
python3 generators/generate_loggers.py log-configs/user_events.json

# Generate only Java
python3 generators/generate_loggers.py log-configs/user_events.json --lang java

# Generate only Python
python3 generators/generate_loggers.py log-configs/user_events.json --lang python

# Custom output directories
python3 generators/generate_loggers.py log-configs/user_events.json \
  --java-output ./custom/path \
  --python-output ./custom/path \
  --script-output ./custom/scripts
```

### From Another Project

#### Option 1: Direct Script Invocation

```bash
# Clone or reference this repo
git clone https://github.com/yourorg/structured-logging.git ../structured-logging

# Run generator from your project
python3 ../structured-logging/generators/generate_loggers.py \
  ./your-config.json \
  --java-output ./src/main/java/com/yourapp/logging \
  --python-output ./logging/generated
```

#### Option 2: Install as Python Package

```bash
# From the structured-logging directory
pip install -e generators/

# Now use from anywhere
structured-logger-generate your-config.json --lang java
```

#### Option 3: Copy Generator Script

```bash
# Copy just the generator (standalone, no dependencies)
cp structured-logging/generators/generate_loggers.py your-project/tools/
python3 your-project/tools/generate_loggers.py your-config.json
```

## Configuration File Format

Create a JSON file describing your log schema:

```json
{
  "name": "UserEvents",
  "log_type": "user_events",
  "version": "1.0.0",
  "description": "Tracks user interaction events",
  "kafka": {
    "topic": "user-events",
    "partitions": 6,
    "replication_factor": 3,
    "retention_ms": 2592000000
  },
  "warehouse": {
    "table_name": "analytics_logs.user_events",
    "partition_by": ["event_date", "event_type"],
    "sort_by": ["timestamp", "user_id"]
  },
  "fields": [
    {
      "name": "timestamp",
      "type": "timestamp",
      "required": true,
      "description": "Event timestamp"
    },
    {
      "name": "user_id",
      "type": "string",
      "required": true,
      "description": "Unique user identifier"
    },
    {
      "name": "event_type",
      "type": "string",
      "required": true,
      "description": "Type of event (click, view, etc.)"
    },
    {
      "name": "properties",
      "type": "map<string,string>",
      "required": false,
      "description": "Additional event properties"
    }
  ]
}
```

### Supported Field Types

| Type | Java | Python | Description |
|------|------|--------|-------------|
| `string` | `String` | `str` | Text |
| `int` | `Integer` | `int` | 32-bit integer |
| `long` | `Long` | `int` | 64-bit integer |
| `float` | `Float` | `float` | 32-bit decimal |
| `double` | `Double` | `float` | 64-bit decimal |
| `boolean` | `Boolean` | `bool` | True/false |
| `timestamp` | `Instant` | `datetime` | ISO-8601 timestamp |
| `date` | `LocalDate` | `date` | Date only (no time) |
| `array<string>` | `List<String>` | `List[str]` | List of strings |
| `map<string,string>` | `Map<String,String>` | `Dict[str,str]` | String key-value pairs |

## Generated Code

### Java Example

```java
// Usage
UserEventsLogger logger = new UserEventsLogger();

logger.log(
    Instant.now(),           // timestamp
    LocalDate.now(),         // event_date
    "user_123",              // user_id
    "session_abc",           // session_id
    "click",                 // event_type
    "/products/laptop",      // page_url
    Map.of("source", "web"), // properties
    "desktop",               // device_type
    150L                     // duration_ms
);

logger.close();
```

**Generated Message Format (in Kafka):**
```json
{
  "_log_type": "user_events",
  "_log_class": "UserEvents",
  "_version": "1.0.0",
  "data": {
    "timestamp": "2026-01-31T12:00:00Z",
    "event_date": "2026-01-31",
    "user_id": "user_123",
    "session_id": "session_abc",
    "event_type": "click",
    "page_url": "/products/laptop",
    "properties": {"source": "web"},
    "device_type": "desktop",
    "duration_ms": 150
  }
}
```

### Python Example

```python
from structured_logging.generated import UserEventsLogger

logger = UserEventsLogger()

logger.log(
    timestamp=datetime.now(),
    event_date=date.today(),
    user_id="user_123",
    session_id="session_abc",
    event_type="click",
    page_url="/products/laptop",
    properties={"source": "web"},
    device_type="desktop",
    duration_ms=150
)

logger.close()
```

## Envelope Format

All generated loggers wrap business data in an envelope with routing metadata:

- **`_log_type`**: Log type identifier (e.g., `"user_events"`) - used for routing in shared topics
- **`_log_class`**: Logger class name (e.g., `"UserEvents"`) - for debugging
- **`_version`**: Schema version (e.g., `"1.0.0"`) - for compatibility tracking
- **`data`**: Your actual business log fields

This envelope enables:
- **Shared Kafka topics**: Multiple log types can share one topic, routed by `_log_type`
- **Schema evolution**: Track versions for backward compatibility
- **Multi-tenancy**: Easy to add tenant/environment IDs later
- **Debugging**: Identify which logger produced a message

## Consumer Integration

Your Spark/Flink consumer must parse the envelope format. See `spark-consumer/kafka-to-iceberg-consumer.py` for reference implementation:

```python
# Create envelope schema
envelope_schema = StructType([
    StructField("_log_type", StringType(), False),
    StructField("_log_class", StringType(), False),
    StructField("_version", StringType(), False),
    StructField("data", your_business_schema, False)
])

# Parse and extract data
df = kafka_df.select(
    from_json(col("value").cast("string"), envelope_schema).alias("envelope")
).select("envelope.data.*")  # Extract business fields
```

## Testing

Run integration tests to verify envelope format end-to-end:

```bash
# From project root
python3 tests/test_envelope_integration.py
```

The test verifies:
1. ✅ Kafka messages have envelope structure
2. ✅ Consumer correctly parses envelope and extracts data
3. ✅ Data flows end-to-end to Iceberg tables

## Generated Files

For each config file, the generator produces:

### Java Logger
- **File**: `UserEventsLogger.java`
- **Location**: `--java-output` directory (default: `java-logger/src/main/java/com/logging/generated/`)
- **Contents**: Logger class with type-safe log methods, builder pattern, envelope wrapping

### Python Logger
- **File**: `userevents_logger.py`
- **Location**: `--python-output` directory (default: `python-logger/structured_logging/generated/`)
- **Contents**: Logger class with type hints, builder, envelope wrapping

### Kafka Topic Script
- **File**: `create-topic-user-events.sh`
- **Location**: `--script-output` directory (default: `scripts/`)
- **Contents**: Executable bash script to create Kafka topic with correct partitions, replication, retention

## CI/CD Integration

### Makefile Example

```makefile
.PHONY: generate-loggers
generate-loggers:
	python3 generators/generate_loggers.py log-configs/user_events.json
	python3 generators/generate_loggers.py log-configs/api_metrics.json
	mvn compile  # Recompile Java

.PHONY: test
test: generate-loggers
	mvn test
	python3 tests/test_envelope_integration.py
```

### GitHub Actions Example

```yaml
name: Generate and Test Loggers

on: [push, pull_request]

jobs:
  generate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      
      - name: Setup Python
        uses: actions/setup-python@v4
        with:
          python-version: '3.9'
      
      - name: Generate loggers
        run: |
          for config in log-configs/*.json; do
            python3 generators/generate_loggers.py "$config"
          done
      
      - name: Compile Java
        run: mvn clean package
      
      - name: Run integration tests
        run: python3 tests/test_envelope_integration.py
```

## Troubleshooting

### Envelope Not Appearing in Messages

1. **Rebuild logger library**: `mvn clean package` (Java) or regenerate Python
2. **Check BaseStructuredLogger**: Ensure `publish()` method wraps with envelope
3. **Verify Kafka message**: Use `kafka-console-consumer` to inspect raw messages

### Consumer Can't Parse Envelope

1. **Check envelope schema**: Ensure consumer uses `StructField("_log_type", ...)` etc.
2. **Extract data field**: Use `.select("envelope.data.*")` not `.select("envelope.*")`
3. **Verify field types**: Date fields should match (string vs date type)

### Schema Evolution

When updating a log config:

1. **Increment version**: Change `"version": "1.0.0"` to `"version": "1.1.0"`
2. **Regenerate code**: Run generator with updated config
3. **Update consumer**: Handle both old and new versions if needed
4. **Deploy consumer first**: Ensures it can handle new format before sending

## See Also

- **`ENVELOPE_FORMAT.md`**: Detailed envelope format specification
- **`spark-consumer/SHARED_TOPIC_IMPLEMENTATION.md`**: Consumer implementation guide
- **`examples/JavaExample.java`**: Complete working example

## Support

For questions or issues:
1. Check `ENVELOPE_FORMAT.md` for envelope details
2. Run `python3 tests/test_envelope_integration.py` to verify setup
3. Inspect Kafka messages: `docker exec kafka kafka-console-consumer --topic your-topic ...`
