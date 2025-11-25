# Schema Naming for Hive Compatibility

## Important Note

Hive Metastore only supports **2-part table names** in the format `database.table`.

### ❌ NOT Supported
```
analytics.logs.user_events  (3 parts: database.schema.table)
```

### ✅ Supported
```
analytics_logs.user_events  (2 parts: database.table)
```

## Current Schema

All tables in this system use the `analytics_logs` database:

- `iceberg.analytics_logs.user_events`
- `iceberg.analytics_logs.api_metrics`

## Why This Matters

When creating new log configurations, use flat database names:

```json
{
  "warehouse": {
    "table_name": "analytics_logs.my_table",  // ✅ Good
    "table_name": "analytics.logs.my_table",   // ❌ Won't work with Hive
  }
}
```

Common naming patterns:
- `analytics_logs.event_name`
- `app_logs.service_name`
- `system_metrics.metric_name`
