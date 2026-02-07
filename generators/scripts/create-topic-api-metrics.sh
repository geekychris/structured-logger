#!/bin/bash
# Kafka topic creation script for ApiMetrics
# Auto-generated - do not edit manually

KAFKA_CONTAINER="kafka"
BOOTSTRAP_SERVER="localhost:9092"

echo "Creating Kafka topic for ApiMetrics..."

docker exec $KAFKA_CONTAINER kafka-topics \
  --bootstrap-server $BOOTSTRAP_SERVER \
  --create \
  --if-not-exists \
  --topic api-metrics \
  --partitions 4 \
  --replication-factor 3 \
  --config retention.ms=604800000 \
  || echo "  ✗ Failed (topic may already exist)"

echo "  ✓ Topic api-metrics configured (4 partitions, replication: 3)"

echo ""
echo "Verify topic:"
docker exec $KAFKA_CONTAINER kafka-topics --bootstrap-server $BOOTSTRAP_SERVER --describe --topic api-metrics
