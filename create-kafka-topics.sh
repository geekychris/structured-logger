#!/bin/bash
# Auto-generated Kafka topic creation script
# Generated at: Fri Jan 30 14:55:52 PST 2026

KAFKA_CONTAINER="kafka"
BOOTSTRAP_SERVER="localhost:9092"

echo "Creating Kafka topics from log configs..."


# Topic: api-metrics (from ApiMetrics)
echo "Creating topic: api-metrics (4 partitions, replication: 1)..."
docker exec $KAFKA_CONTAINER kafka-topics \
  --bootstrap-server $BOOTSTRAP_SERVER \
  --create \
  --if-not-exists \
  --topic api-metrics \
  --partitions 4 \
  --replication-factor 1 \
  --config retention.ms=604800000 \
  || echo "  ✗ Failed (topic may already exist)"
echo "  ✓ Topic api-metrics configured"

# Topic: user-activity (from UserActivityLog)
echo "Creating topic: user-activity (3 partitions, replication: 1)..."
docker exec $KAFKA_CONTAINER kafka-topics \
  --bootstrap-server $BOOTSTRAP_SERVER \
  --create \
  --if-not-exists \
  --topic user-activity \
  --partitions 3 \
  --replication-factor 1 \
  --config retention.ms=604800000 \
  || echo "  ✗ Failed (topic may already exist)"
echo "  ✓ Topic user-activity configured"

# Topic: user-events (from UserEvents)
echo "Creating topic: user-events (6 partitions, replication: 1)..."
docker exec $KAFKA_CONTAINER kafka-topics \
  --bootstrap-server $BOOTSTRAP_SERVER \
  --create \
  --if-not-exists \
  --topic user-events \
  --partitions 6 \
  --replication-factor 1 \
  --config retention.ms=2592000000 \
  || echo "  ✗ Failed (topic may already exist)"
echo "  ✓ Topic user-events configured"

echo ""
echo "Topic creation complete. Listing topics:"
docker exec $KAFKA_CONTAINER kafka-topics --bootstrap-server $BOOTSTRAP_SERVER --list
