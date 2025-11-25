#!/bin/bash
#
# Schema Evolution Demo
# 
# This script demonstrates automatic schema evolution in the Structured Logging System.
# It shows how to add new fields to an existing log config and watch the consumer
# automatically evolve the Iceberg table schema.
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo "=========================================="
echo "Schema Evolution Demo"
echo "=========================================="
echo

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

step() {
    echo -e "${BLUE}▶ $1${NC}"
}

success() {
    echo -e "${GREEN}✓ $1${NC}"
}

info() {
    echo -e "${YELLOW}ℹ $1${NC}"
}

# Check prerequisites
step "Checking prerequisites..."
if ! docker ps | grep -q spark-master; then
    echo "Error: Spark container not running. Start with: docker-compose up -d"
    exit 1
fi
success "Docker containers running"

if ! docker ps | grep -q kafka; then
    echo "Error: Kafka container not running. Start with: docker-compose up -d"
    exit 1
fi
success "Kafka running"

# Step 1: Initial schema
step "Step 1: Creating initial log config with basic fields"
cat > "$PROJECT_ROOT/log-configs/demo_events.json" << 'EOF'
{
  "name": "DemoEvents",
  "version": "1.0.0",
  "description": "Demo log for schema evolution",
  "kafka": {
    "topic": "demo-events",
    "partitions": 3,
    "replication_factor": 1,
    "retention_ms": 86400000
  },
  "warehouse": {
    "tableName": "analytics_logs.demo_events",
    "partitionBy": ["event_date"],
    "sortBy": ["timestamp"],
    "retentionDays": 7
  },
  "fields": [
    {
      "name": "timestamp",
      "type": "timestamp",
      "required": true,
      "description": "Event timestamp"
    },
    {
      "name": "event_date",
      "type": "date",
      "required": true,
      "description": "Date for partitioning"
    },
    {
      "name": "user_id",
      "type": "string",
      "required": true,
      "description": "User identifier"
    },
    {
      "name": "event_type",
      "type": "string",
      "required": true,
      "description": "Type of event"
    }
  ]
}
EOF

success "Created initial config with 4 fields: timestamp, event_date, user_id, event_type"
echo

# Step 2: Start consumer with initial schema
step "Step 2: Starting Spark consumer to create table with initial schema"
info "This will create the Iceberg table with the initial schema..."
docker exec spark-master pkill -f StructuredLogConsumer 2>/dev/null || true
sleep 2

docker exec -d spark-master bash -c "
    cd /opt/spark && \
    /opt/spark/bin/spark-submit \
        --master local[*] \
        --class com.logging.consumer.StructuredLogConsumer \
        --conf spark.executor.memory=2g \
        --conf spark.driver.memory=2g \
        --packages org.apache.iceberg:iceberg-spark-runtime-3.5_2.12:1.4.0,org.apache.kafka:kafka-clients:3.3.1,software.amazon.awssdk:bundle:2.17.257,software.amazon.awssdk:url-connection-client:2.17.257 \
        /opt/spark-apps-java/structured-log-consumer-1.0.0.jar \
        /opt/spark-apps/log-configs \
        kafka:29092 \
        s3a://warehouse \
        s3a://warehouse/checkpoints \
        > /opt/spark-data/consumer.log 2>&1
"

sleep 10
success "Consumer started"
echo

step "Step 3: Checking initial table schema"
docker exec trino trino --execute "DESCRIBE iceberg.analytics_logs.demo_events" || info "Table not yet created (will be created on first write)"
echo

# Step 4: Generate some initial data
step "Step 4: Generating initial test data (10 events)"
python3 - << 'PYEOF'
import json
from datetime import datetime, date
from kafka import KafkaProducer

producer = KafkaProducer(
    bootstrap_servers='localhost:9092',
    value_serializer=lambda v: json.dumps(v).encode('utf-8')
)

for i in range(10):
    event = {
        "timestamp": datetime.utcnow().isoformat() + "Z",
        "event_date": date.today().isoformat(),
        "user_id": f"user_{i:03d}",
        "event_type": "click"
    }
    producer.send('demo-events', event)

producer.flush()
producer.close()
print("✓ Sent 10 events to demo-events topic")
PYEOF

sleep 15  # Wait for Spark to process
success "Data generated and processed"
echo

step "Step 5: Querying initial data"
docker exec trino trino --execute "SELECT COUNT(*) as count FROM iceberg.analytics_logs.demo_events"
docker exec trino trino --execute "SELECT * FROM iceberg.analytics_logs.demo_events LIMIT 3"
echo

info "Press ENTER to continue with schema evolution..."
read

# Step 6: Add new fields to config
step "Step 6: Adding new fields to config (country_code, session_id)"
cat > "$PROJECT_ROOT/log-configs/demo_events.json" << 'EOF'
{
  "name": "DemoEvents",
  "version": "2.0.0",
  "description": "Demo log for schema evolution",
  "kafka": {
    "topic": "demo-events",
    "partitions": 3,
    "replication_factor": 1,
    "retention_ms": 86400000
  },
  "warehouse": {
    "tableName": "analytics_logs.demo_events",
    "partitionBy": ["event_date"],
    "sortBy": ["timestamp"],
    "retentionDays": 7
  },
  "fields": [
    {
      "name": "timestamp",
      "type": "timestamp",
      "required": true,
      "description": "Event timestamp"
    },
    {
      "name": "event_date",
      "type": "date",
      "required": true,
      "description": "Date for partitioning"
    },
    {
      "name": "user_id",
      "type": "string",
      "required": true,
      "description": "User identifier"
    },
    {
      "name": "event_type",
      "type": "string",
      "required": true,
      "description": "Type of event"
    },
    {
      "name": "country_code",
      "type": "string",
      "required": false,
      "description": "User country code (NEW)"
    },
    {
      "name": "session_id",
      "type": "string",
      "required": false,
      "description": "Session identifier (NEW)"
    }
  ]
}
EOF

success "Updated config - added 2 new optional fields"
echo

# Step 7: Restart consumer to trigger schema evolution
step "Step 7: Restarting consumer to trigger schema evolution"
info "Watch for schema evolution messages in consumer logs..."

docker exec spark-master pkill -f StructuredLogConsumer 2>/dev/null || true
sleep 2

docker exec -d spark-master bash -c "
    cd /opt/spark && \
    /opt/spark/bin/spark-submit \
        --master local[*] \
        --class com.logging.consumer.StructuredLogConsumer \
        --conf spark.executor.memory=2g \
        --conf spark.driver.memory=2g \
        --packages org.apache.iceberg:iceberg-spark-runtime-3.5_2.12:1.4.0,org.apache.kafka:kafka-clients:3.3.1,software.amazon.awssdk:bundle:2.17.257,software.amazon.awssdk:url-connection-client:2.17.257 \
        /opt/spark-apps-java/structured-log-consumer-1.0.0.jar \
        /opt/spark-apps/log-configs \
        kafka:29092 \
        s3a://warehouse \
        s3a://warehouse/checkpoints \
        > /opt/spark-data/consumer.log 2>&1
"

sleep 5
echo
info "Consumer logs:"
docker exec spark-master tail -20 /opt/spark-data/consumer.log | grep -E "(Schema|column|ALTER TABLE)" || true
echo

sleep 5
success "Consumer restarted with new schema"
echo

# Step 8: Check evolved schema
step "Step 8: Checking evolved table schema"
docker exec trino trino --execute "DESCRIBE iceberg.analytics_logs.demo_events"
echo

# Step 9: Generate new data with new fields
step "Step 9: Generating new data with additional fields (10 events)"
python3 - << 'PYEOF'
import json
import random
from datetime import datetime, date
from kafka import KafkaProducer

producer = KafkaProducer(
    bootstrap_servers='localhost:9092',
    value_serializer=lambda v: json.dumps(v).encode('utf-8')
)

countries = ["US", "UK", "DE", "FR", "JP"]

for i in range(10):
    event = {
        "timestamp": datetime.utcnow().isoformat() + "Z",
        "event_date": date.today().isoformat(),
        "user_id": f"user_{i:03d}",
        "event_type": "click",
        "country_code": random.choice(countries),  # NEW FIELD
        "session_id": f"sess_{i:04d}"             # NEW FIELD
    }
    producer.send('demo-events', event)

producer.flush()
producer.close()
print("✓ Sent 10 events with new fields to demo-events topic")
PYEOF

sleep 15  # Wait for Spark to process
success "New data generated and processed"
echo

# Step 10: Query showing old and new data
step "Step 10: Querying mixed data (old rows have NULL for new fields)"
echo
echo "Total records:"
docker exec trino trino --execute "SELECT COUNT(*) as total_records FROM iceberg.analytics_logs.demo_events"
echo

echo "Records by country_code (NULL = old records):"
docker exec trino trino --execute "
    SELECT 
        country_code, 
        COUNT(*) as count 
    FROM iceberg.analytics_logs.demo_events 
    GROUP BY country_code
    ORDER BY country_code NULLS FIRST
"
echo

echo "Sample records (showing old and new):"
docker exec trino trino --execute "
    SELECT 
        user_id,
        event_type,
        country_code,
        session_id
    FROM iceberg.analytics_logs.demo_events 
    ORDER BY _ingestion_timestamp
    LIMIT 6
"
echo

# Summary
echo "=========================================="
success "Schema Evolution Demo Complete!"
echo "=========================================="
echo
echo "Summary:"
echo "  1. Created table with 4 fields"
echo "  2. Loaded 10 records with original schema"
echo "  3. Added 2 new optional fields to config"
echo "  4. Consumer automatically evolved schema (ALTER TABLE)"
echo "  5. Loaded 10 more records with new fields"
echo "  6. Old records have NULL for new fields ✓"
echo "  7. New records have values for all fields ✓"
echo
info "Key takeaway: Schema evolution is automatic and backward-compatible!"
echo
info "See SCHEMA_EVOLUTION.md for detailed documentation"
echo
echo "Cleanup: rm $PROJECT_ROOT/log-configs/demo_events.json"
echo
