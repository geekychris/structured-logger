#!/bin/bash
# Kafka topic creation script for UserActivityLog
# Auto-generated - do not edit manually

KAFKA_CONTAINER="kafka"
BOOTSTRAP_SERVER="localhost:9092"

echo "Creating Kafka topic for UserActivityLog..."

docker exec $KAFKA_CONTAINER kafka-topics \
  --bootstrap-server $BOOTSTRAP_SERVER \
  --create \
  --if-not-exists \
  --topic user-activity \
  --partitions 3 \
  --replication-factor 1 \
  --config retention.ms=604800000 \
  || echo "  ✗ Failed (topic may already exist)"

echo "  ✓ Topic user-activity configured (3 partitions, replication: 1)"

echo ""
echo "Verify topic:"
docker exec $KAFKA_CONTAINER kafka-topics --bootstrap-server $BOOTSTRAP_SERVER --describe --topic user-activity
