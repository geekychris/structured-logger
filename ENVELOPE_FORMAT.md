# Message Envelope Format

## Overview

All log messages now include an envelope with routing metadata to support future multi-tenancy and shared topic architectures.

## Message Structure

### Envelope Format

Every message sent to Kafka is wrapped in an envelope:

```json
{
  "_log_type": "user_events",
  "_log_class": "UserEvents",
  "_version": "1.0.0",
  "data": {
    "user_id": "12345",
    "timestamp": "2026-01-30T17:00:00Z",
    "event_type": "LOGIN",
    ...business fields...
  }
}
```

### Metadata Fields

| Field | Description | Example |
|-------|-------------|---------|
| `_log_type` | Log type identifier for routing | `"user_events"`, `"api_metrics"` |
| `_log_class` | Logger class name | `"UserEvents"`, `"ApiMetrics"` |
| `_version` | Schema version | `"1.0.0"` |
| `data` | Business log data (your actual log fields) | `{...}` |

## Why?

### Future-Proofing

The envelope enables:

1. **Shared Topics**: Multiple log types can share a Kafka topic, with the consumer routing by `_log_type`
2. **Schema Evolution**: Track schema versions for compatibility checks
3. **Multi-Tenancy**: Easy to add tenant/environment identifiers later
4. **Debugging**: Identify which logger class produced a message

### Current Architecture

**Today (Dedicated Topics):**
```
Logger: UserEventsLogger
  → Topic: user-events
  → Consumer reads entire topic
  → Table: analytics_logs.user_events
```

**Future (Shared Topics):**
```
Logger: UserEventsLogger → Topic: application-logs (_log_type="user_events")
Logger: ApiMetricsLogger → Topic: application-logs (_log_type="api_metrics")
  → Consumer reads topic once
  → Routes by _log_type:
     ├─ user_events → analytics_logs.user_events
     └─ api_metrics → analytics_logs.api_metrics
```

## Configuration

### log_type Field

Add `log_type` to your log config JSON:

```json
{
  "name": "UserEvents",
  "log_type": "user_events",  ← Routing identifier
  "version": "1.0.0",
  "kafka": {
    "topic": "user-events"
  }
}
```

If `log_type` is omitted, it defaults to the lowercase version of `name`.

## Code Generation

### Updated Generator

The logger generator now:

1. ✅ Includes `log_type` and `version` in generated loggers
2. ✅ Wraps messages in envelope automatically (in `BaseStructuredLogger`)
3. ✅ **Generates topic creation scripts** for each logger

### Generated Files

When you run:
```bash
python3 generators/generate_loggers.py log-configs/user_events.json
```

You get:
- **Logger code**: `java-logger/src/main/java/com/logging/generated/UserEventsLogger.java`
- **Topic script**: `scripts/create-topic-user-events.sh` ← **NEW!**

### Topic Creation Scripts

Each generated script creates the Kafka topic with correct settings:

```bash
#!/bin/bash
# Kafka topic creation script for UserEvents
# Auto-generated - do not edit manually

KAFKA_CONTAINER="kafka"
BOOTSTRAP_SERVER="localhost:9092"

echo "Creating Kafka topic for UserEvents..."

docker exec $KAFKA_CONTAINER kafka-topics \
  --bootstrap-server $BOOTSTRAP_SERVER \
  --create \
  --if-not-exists \
  --topic user-events \
  --partitions 6 \
  --replication-factor 3 \
  --config retention.ms=2592000000 \
  || echo "  ✗ Failed (topic may already exist)"

echo "  ✓ Topic user-events configured (6 partitions, replication: 3)"
```

**Usage:**
```bash
./scripts/create-topic-user-events.sh
```

## Migration

### Existing Deployments

**No action required!** The envelope is transparent:

1. **Consumer compatibility**: Current Spark consumer expects flat JSON and will break
2. **You must update consumer** to parse envelope format before redeploying loggers

### Consumer Update (Required)

See `spark-consumer/SHARED_TOPIC_IMPLEMENTATION.md` for instructions to update the Spark consumer to:
1. Parse envelope format
2. Extract `data` field
3. (Optional) Route by `_log_type` for shared topics

### Rollout Plan

1. ✅ Update `BaseStructuredLogger` to add envelope
2. ✅ Regenerate all loggers
3. ✅ Generate topic creation scripts
4. ⏳ Update Spark consumer to parse envelope
5. ⏳ Deploy updated consumer first
6. ⏳ Deploy updated logger libraries
7. ⏳ Test end-to-end

## Testing

### Verify Envelope

Send a test message:
```bash
# Run your Java example
./run-java-demo.sh
```

Check Kafka message format:
```bash
docker exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic user-events \
  --from-beginning \
  --max-messages 1 | jq '.'
```

Expected output:
```json
{
  "_log_type": "user_events",
  "_log_class": "UserEvents",
  "_version": "1.0.0",
  "data": {
    "user_id": "user_abc123",
    "timestamp": "2026-01-30T17:00:00Z",
    ...
  }
}
```

## Benefits

✅ **Future-proof**: Ready for shared topics without breaking changes  
✅ **Versioned**: Track schema evolution  
✅ **Debuggable**: Easy to identify message source  
✅ **Automated**: Topic scripts prevent partition mismatches  
✅ **Flexible**: Switch between dedicated/shared topics by changing config

## See Also

- `TOPIC_SHARING.md` - Complete guide to shared topic architecture
- `spark-consumer/SHARED_TOPIC_IMPLEMENTATION.md` - Consumer update instructions
- `generators/generate_loggers.py` - Code generator source
