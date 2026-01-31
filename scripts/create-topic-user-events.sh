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

echo ""
echo "Verify topic:"
docker exec $KAFKA_CONTAINER kafka-topics --bootstrap-server $BOOTSTRAP_SERVER --describe --topic user-events
