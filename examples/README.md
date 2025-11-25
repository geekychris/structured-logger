# Examples

This directory contains example code and utilities for the Structured Logging System.

## Test Data Generator

`generate_test_data.py` - Generate realistic test data for load testing and demonstrations.

### Quick Start

```bash
# Generate 100 events
python3 examples/generate_test_data.py

# Generate 1000 events  
python3 examples/generate_test_data.py --count 1000

# Continuous generation at 10 events/second
python3 examples/generate_test_data.py --continuous --rate 10

# Generate for 5 minutes at 5 events/second
python3 examples/generate_test_data.py --duration 300 --rate 5
```

### Features

- **Realistic data patterns**: Variable response times, error rates, user behaviors
- **Two topic types**: User events (60%) and API metrics (40%)
- **Multiple generation modes**: Batch, continuous, or timed duration
- **Configurable rate**: Control events per second for load testing
- **Progress tracking**: Real-time feedback during generation

### Generated Data

**User Events** (user-events topic):
- 100 unique users (user_0001 to user_0100)
- 500 active sessions
- 6 event types: click, page_view, purchase, search, login, logout
- 10 different pages/URLs
- 3 device types: desktop, mobile, tablet
- Realistic duration: 100ms-30s depending on event type
- Properties: browser, OS, referrer

**API Metrics** (api-metrics topic):
- 5 microservices
- 8 API endpoints
- 4 HTTP methods
- 95% success rate (200/201/204)
- 5% errors (400/404/500/503)
- Response times: 10-500ms (success), 100-5000ms (errors)
- Request/response sizes

### Query Results

After generating data, query in Trino:

```sql
-- Count events
SELECT COUNT(*) FROM iceberg.analytics_logs.user_events;
SELECT COUNT(*) FROM iceberg.analytics_logs.api_metrics;

-- User activity
SELECT event_type, COUNT(*) as count 
FROM iceberg.analytics_logs.user_events 
GROUP BY event_type;

-- API performance
SELECT service_name, 
       AVG(response_time) as avg_response,
       COUNT(*) as count
FROM iceberg.analytics_logs.api_metrics 
GROUP BY service_name;

-- Error rates
SELECT status_code, COUNT(*) as count
FROM iceberg.analytics_logs.api_metrics
GROUP BY status_code
ORDER BY status_code;
```

## Simple Examples

### Python Example

`python_example.py` - Basic usage of the Python logger.

```bash
python3 examples/python_example.py
```

Sends a few test events to Kafka using the generated Python logger.

### Java Example

See `java-logger/src/main/java/com/logging/JavaExample.java` for Java logger usage.

```bash
cd java-logger
mvn compile exec:java -Dexec.mainClass="com.logging.JavaExample"
```

## Usage Patterns

### Development Testing
```bash
# Quick test with small batch
python3 examples/generate_test_data.py --count 10
```

### Load Testing
```bash
# High volume for 10 minutes
python3 examples/generate_test_data.py --duration 600 --rate 100
```

### Continuous Demo
```bash
# Live streaming demo
python3 examples/generate_test_data.py --continuous --rate 5
# Press Ctrl+C to stop
```

### Using with Docker Kafka
```bash
# If Kafka is inside Docker
python3 examples/generate_test_data.py --kafka kafka:29092
```

## Schema Evolution Demo

`schema_evolution_demo.sh` - Interactive demonstration of automatic schema evolution.

### What It Does

This script demonstrates the complete schema evolution workflow:

1. Creates an Iceberg table with basic schema (4 fields)
2. Loads initial data (10 records)
3. Updates log config to add new fields
4. Restarts consumer - triggers automatic schema evolution
5. Loads new data with additional fields
6. Shows mixed queries (old data has NULLs for new fields)

### Running the Demo

```bash
# Prerequisites: docker-compose up -d, consumer built
bash examples/schema_evolution_demo.sh

# The demo will:
# - Create demo_events.json config
# - Start consumer to create table
# - Generate test data
# - Evolve schema by adding fields
# - Show before/after queries

# Cleanup after demo
rm log-configs/demo_events.json
```

### Expected Output

```
Step 1: Creating initial config with 4 fields
✓ Created initial config

Step 6: Adding new fields (country_code, session_id)
✓ Updated config - added 2 new optional fields

Step 7: Restarting consumer
Checking schema evolution for: local.analytics_logs.demo_events
  + Adding new column: country_code STRING
  + Adding new column: session_id STRING
Executing: ALTER TABLE local.analytics_logs.demo_events ADD COLUMN country_code STRING
Executing: ALTER TABLE local.analytics_logs.demo_events ADD COLUMN session_id STRING
✓ Schema evolved successfully

Step 10: Querying mixed data
Records by country_code:
  NULL | 10  (old records)
  US   | 2   (new records)
  UK   | 3
  ...
```

See [SCHEMA_EVOLUTION.md](../SCHEMA_EVOLUTION.md) for detailed documentation.

## Requirements

All examples require:
- Python 3.x
- `kafka-python` package: `pip3 install kafka-python`
- Running Kafka instance (docker-compose)
- Running Spark consumer to process events
