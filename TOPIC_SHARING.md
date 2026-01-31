# Topic Sharing Strategy

## Overview

This document describes how to share Kafka topics across multiple log classes while maintaining proper routing and separation in the data warehouse.

## Concepts

### Log Type Discriminator

Each log message includes metadata fields that identify its log class:

```json
{
  "_log_type": "user_activity",
  "_log_class": "UserActivityLog", 
  "_version": "1.0.0",
  "_ingestion_timestamp": "2026-01-30T17:00:00Z",
  ...business fields...
}
```

### Topic Multiplexing

Multiple log classes can share a single Kafka topic. The Spark consumer uses the `_log_type` field to route messages to the appropriate Iceberg table.

## Configuration

### Option 1: Dedicated Topics (Current)

Each log class has its own topic:

```json
{
  "name": "UserActivityLog",
  "kafka": {
    "topic": "user-activity",
    "partitions": 3,
    "replication_factor": 1
  }
}
```

### Option 2: Shared Topic

Multiple log classes share a topic:

```json
{
  "name": "UserActivityLog",
  "log_type": "user_activity",
  "kafka": {
    "topic": "application-logs",  // Shared topic
    "partitions": 10,              // Sized for all classes
    "replication_factor": 3
  }
}
```

```json
{
  "name": "ApiMetrics",
  "log_type": "api_metrics",
  "kafka": {
    "topic": "application-logs",  // Same shared topic
    "partitions": 10,
    "replication_factor": 3
  }
}
```

## Benefits of Shared Topics

### Advantages

1. **Fewer Topics**: Reduce Kafka operational overhead
2. **Better Resource Utilization**: Share partition leaders across log types
3. **Simplified Monitoring**: One topic to monitor instead of many
4. **Easier Scaling**: Add new log classes without creating topics

### When to Use

- **Many log classes** with low-to-medium volume
- **Related logs** (e.g., all application logs, all metrics)
- **Standardized schemas** with common metadata

### When NOT to Use

- **High-volume logs**: Dedicated topics for better isolation
- **Different retention requirements**: Each needs separate config
- **Security boundaries**: Different access control needs
- **Different performance profiles**: Latency vs throughput tradeoffs

## Topic Creation

### Generate Creation Script

```bash
cd generators
./generate-topic-script.sh ../log-configs ../create-kafka-topics.sh
```

This generates a script that:
- ✅ Reads all JSON configs
- ✅ Deduplicates shared topics
- ✅ Uses highest partition count for shared topics
- ✅ Applies correct retention settings
- ✅ Uses `--if-not-exists` for idempotency

### Run Creation Script

```bash
./create-kafka-topics.sh
```

Output:
```
Creating Kafka topics from log configs...

Creating topic: api-metrics (4 partitions, replication: 3)...
  ✓ Topic api-metrics configured

Creating topic: user-activity (3 partitions, replication: 1)...
  ✓ Topic user-activity configured
```

## Consumer Behavior

The Spark consumer:

1. Reads from Kafka topic(s)
2. Extracts `_log_type` from each message
3. Routes to correct Iceberg table based on config mapping
4. Writes with appropriate partitioning per table

### Message Routing

```
Kafka Topic: application-logs
│
├─ Message { "_log_type": "user_activity", ... }
│  └─> Iceberg Table: analytics_logs.user_activity
│
├─ Message { "_log_type": "api_metrics", ... }
│  └─> Iceberg Table: analytics_logs.api_metrics
│
└─ Message { "_log_type": "audit_log", ... }
   └─> Iceberg Table: analytics_logs.audit_log
```

## Implementation Checklist

To implement shared topics:

- [ ] Add `log_type` field to JSON configs
- [ ] Update logger generator to include `_log_type` in messages
- [ ] Update Spark consumer to route by `_log_type`
- [ ] Set `kafka.topic` to same value for log classes sharing a topic
- [ ] Run topic creation script
- [ ] Test message routing
- [ ] Update monitoring dashboards

## Best Practices

### Naming

- **Dedicated topics**: Use descriptive names (`user-events`, `api-metrics`)
- **Shared topics**: Use category names (`application-logs`, `system-metrics`)

### Partitioning

- **Dedicated**: Size for specific log class volume
- **Shared**: Size for aggregate volume across all classes
- **Rule of thumb**: 10-20 partitions for shared topics

### Retention

- **Shared topics**: Use longest retention requirement
- **Per-table retention**: Handle in Iceberg table properties

### Monitoring

Track metrics per `_log_type`:
- Message rate
- Processing lag
- Error rate
- Table growth

## Migration

### From Dedicated to Shared

1. Create new shared topic with appropriate sizing
2. Update configs to use shared topic
3. Restart consumer (will read from new topic)
4. Update producers to send to new topic
5. Wait for retention period on old topics
6. Delete old dedicated topics

### From Shared to Dedicated

1. Create dedicated topic for specific log class
2. Update that log class config
3. Restart consumer
4. Update producer for that class
5. Monitor to ensure routing is correct
6. Reduce shared topic partition count if desired
