# Configuration Flow Diagram

This diagram shows how log configurations flow through the system and how the Spark consumer automatically discovers them.

## Visual Flow

```
┌─────────────────────────────────────────────────────────────────────┐
│                          HOST MACHINE                                │
│                                                                       │
│  structured-logging/                                                 │
│  └── log-configs/                                                    │
│      ├── api_metrics.json      ◄─── You create/edit configs here    │
│      ├── user_events.json                                            │
│      └── database_metrics.json ◄─── New config you just added       │
│                                                                       │
└───────────────────┬────────────────────────────────┬─────────────────┘
                    │                                │
                    │                                │
        ┌───────────▼──────────┐      ┌──────────────▼────────────────┐
        │   Code Generator     │      │   Docker Volume Mount         │
        │   (Python Script)    │      │                               │
        │                      │      │   Host: ./log-configs/        │
        │  When you run:       │      │   Container:                  │
        │  make generate       │      │   /opt/spark-apps/log-configs/│
        │                      │      │                               │
        │  Reads configs and   │      │   Real-time sync!             │
        │  generates:          │      │   No copying needed           │
        └───────────┬──────────┘      └──────────────┬────────────────┘
                    │                                │
                    ↓                                ↓
        ┌───────────────────────┐      ┌────────────────────────────────┐
        │  Generated Loggers    │      │   Spark Container              │
        │                       │      │   spark-master                 │
        │  Java:                │      │                                │
        │  java-logger/src/     │      │   StructuredLogConsumer        │
        │    main/java/com/     │      │                                │
        │    logging/generated/ │      │   loadConfigs() method reads:  │
        │  • ApiMetricsLogger   │      │   /opt/spark-apps/log-configs/ │
        │  • UserEventsLogger   │      │                                │
        │  • DatabaseMetrics... │      │   Discovers ALL *.json files   │
        │                       │      │                                │
        │  Python:              │      │   For each config:             │
        │  python-logger/       │      │   1. Subscribe to Kafka topic  │
        │    structured_logging/│      │   2. Parse schema              │
        │    generated/         │      │   3. Create Iceberg table      │
        │  • api_metrics_...    │      │   4. Start streaming query     │
        │  • user_events_...    │      │                                │
        │  • database_metrics...|      │                                │
        └───────────────────────┘      └────────────────────────────────┘
```

## Step-by-Step: Adding a New Logger

```
STEP 1: Create Config
┌────────────────────────────────────────┐
│ You create:                            │
│ log-configs/database_metrics.json      │
│                                        │
│ {                                      │
│   "name": "DatabaseMetrics",           │
│   "kafka": {                           │
│     "topic": "database-metrics"        │
│   },                                   │
│   "warehouse": {                       │
│     "table_name": "analytics_logs...   │
│   },                                   │
│   "fields": [...]                      │
│ }                                      │
└────────────────────────────────────────┘
                 │
                 ↓
STEP 2: Generate Logger Code
┌────────────────────────────────────────┐
│ Run command:                           │
│ $ make generate                        │
│                                        │
│ OR:                                    │
│ $ python generators/                   │
│   generate_loggers.py                  │
│   log-configs/database_metrics.json    │
│                                        │
│ Generator creates type-safe classes    │
└────────────────────────────────────────┘
                 │
                 ↓
STEP 3: Restart Spark Consumer
┌────────────────────────────────────────┐
│ Run command:                           │
│ $ ./start-consumer.sh                  │
│                                        │
│ Consumer automatically:                │
│ ✓ Finds database_metrics.json          │
│   (via Docker volume mount)            │
│ ✓ Subscribes to "database-metrics"     │
│ ✓ Creates Iceberg table                │
│ ✓ Starts streaming                     │
│                                        │
│ No manual copying required!            │
└────────────────────────────────────────┘
                 │
                 ↓
STEP 4: Use in Application
┌────────────────────────────────────────┐
│ Java:                                  │
│ try (DatabaseMetricsLogger logger =    │
│      new DatabaseMetricsLogger()) {    │
│   logger.log(...);                     │
│ }                                      │
│                                        │
│ Python:                                │
│ with DatabaseMetricsLogger() as logger:│
│   logger.log(...)                      │
└────────────────────────────────────────┘
                 │
                 ↓
STEP 5: Data Flows Automatically
┌────────────────────────────────────────┐
│ Application                            │
│   ↓ Publishes to Kafka                 │
│ Kafka (database-metrics topic)         │
│   ↓ Consumed by Spark                  │
│ Spark Consumer                         │
│   ↓ Writes to Iceberg                  │
│ Iceberg Table                          │
│   (analytics_logs.database_metrics)    │
│   ↓ Queryable via Trino                │
│ SELECT * FROM                          │
│   local.analytics_logs.database_metrics│
└────────────────────────────────────────┘
```

## Key Insight: No Manual Copying!

### ❌ OLD WAY (Manual Copying)
```
1. Create config on host
2. Generate logger
3. Manually copy config to shared location  ← Error-prone!
4. Restart consumer
```

### ✅ NEW WAY (Docker Volume Mount)
```
1. Create config in log-configs/           ← Single location
2. Generate logger
3. Restart consumer                         ← Auto-discovers!
```

The magic is in `docker-compose.yml`:

```yaml
services:
  spark-master:
    volumes:
      - ./log-configs:/opt/spark-apps/log-configs
```

This mount means:
- **Host path**: `./log-configs/` 
- **Container path**: `/opt/spark-apps/log-configs/`
- **Effect**: Same files, no copying needed!

## Consumer Discovery Logic

When `StructuredLogConsumer` starts:

```java
private static List<LogConfig> loadConfigs(String configPath) {
    Path path = Paths.get(configPath);  // /opt/spark-apps/log-configs
    
    if (Files.isDirectory(path)) {
        // Load ALL JSON files from directory
        return Files.list(path)
                    .filter(p -> p.toString().endsWith(".json"))
                    .map(this::loadConfig)
                    .collect(Collectors.toList());
    }
}
```

Result: Consumer finds ALL configs automatically!

## Benefits

✅ **Single Source of Truth**: All configs in `log-configs/`  
✅ **No Manual Steps**: Volume mount keeps files in sync  
✅ **Version Controlled**: Git tracks all configs together  
✅ **Easy Updates**: Edit config, restart consumer, done!  
✅ **Multi-Tenant**: One consumer handles all log types  
✅ **Fast Development**: Add new logger in ~5 minutes  

## Related Documentation

- [ADDING_NEW_LOGGERS.md](../ADDING_NEW_LOGGERS.md) - Detailed step-by-step guide
- [ARCHITECTURE.md](../ARCHITECTURE.md) - System architecture overview
- [docker-compose.yml](../docker-compose.yml) - Volume mount configuration
