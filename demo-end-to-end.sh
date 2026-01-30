#!/bin/bash

echo "=========================================="
echo "Structured Logging - End-to-End Demo"
echo "=========================================="
echo ""

echo "This demo will:"
echo "  1. Send log events from Java"
echo "  2. Verify they're in Kafka"
echo "  3. Check Spark consumer status"
echo "  4. Show the data pipeline"
echo ""
read -p "Press Enter to continue..."
echo ""

# Step 1: Send Java log events
echo "Step 1: Generating log events..."
echo "========================================"
./run-java-demo.sh 2>&1 | grep -E "(✓|User events|API metrics|Exception)" || true
echo ""

# Step 2: Verify messages in Kafka
echo "Step 2: Verifying messages in Kafka..."
echo "========================================"
echo "Checking user-events topic:"
MESSAGES=$(docker exec trino_docker-kafka-1 kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic user-events \
  --from-beginning \
  --max-messages 1 \
  --timeout-ms 3000 2>/dev/null | wc -l)

if [ "$MESSAGES" -gt 0 ]; then
    echo "✓ Found messages in user-events topic"
    docker exec trino_docker-kafka-1 kafka-console-consumer \
      --bootstrap-server localhost:9092 \
      --topic user-events \
      --from-beginning \
      --max-messages 1 \
      --timeout-ms 3000 2>/dev/null | head -1
else
    echo "❌ No messages found in user-events topic"
fi
echo ""

echo "Checking api-metrics topic:"
MESSAGES=$(docker exec trino_docker-kafka-1 kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic api-metrics \
  --from-beginning \
  --max-messages 1 \
  --timeout-ms 3000 2>/dev/null | wc -l)

if [ "$MESSAGES" -gt 0 ]; then
    echo "✓ Found messages in api-metrics topic"
    docker exec trino_docker-kafka-1 kafka-console-consumer \
      --bootstrap-server localhost:9092 \
      --topic api-metrics \
      --from-beginning \
      --max-messages 1 \
      --timeout-ms 3000 2>/dev/null | head -1
else
    echo "❌ No messages found in api-metrics topic"
fi
echo ""

# Step 3: Check Spark consumer status
echo "Step 3: Checking Spark Consumer..."
echo "========================================"
CONSUMER_RUNNING=$(docker exec trino_docker-spark-master-1 ps aux | grep -c StructuredLogConsumer || echo "0")
if [ "$CONSUMER_RUNNING" -gt 1 ]; then
    echo "✓ Spark consumer is running"
    echo ""
    echo "Recent consumer activity:"
    docker exec trino_docker-spark-master-1 tail -20 /opt/spark-data/consumer.log | \
      grep -E "(Processing|Created table|Started streaming|wrote)" || \
      echo "  (Consumer is initializing...)"
else
    echo "❌ Spark consumer is not running"
    echo "  Restart with: ./start-demo-with-trino-docker.sh"
fi
echo ""

# Step 4: Show the architecture
echo "Step 4: Data Pipeline Status"
echo "========================================"
echo ""
echo "┌─────────────────┐"
echo "│  Java App       │  ✓ Sent 4 log events"
echo "└────────┬────────┘"
echo "         │"
echo "         ↓"
echo "┌─────────────────┐"
echo "│     Kafka       │  ✓ Messages queued"
echo "│  localhost:9092 │"
echo "└────────┬────────┘"
echo "         │"
echo "         ↓"
echo "┌─────────────────┐"
if [ "$CONSUMER_RUNNING" -gt 1 ]; then
echo "│ Spark Consumer  │  ✓ Processing (10s batches)"
else
echo "│ Spark Consumer  │  ⚠  Starting up..."
fi
echo "└────────┬────────┘"
echo "         │"
echo "         ↓"
echo "┌─────────────────┐"
echo "│ Iceberg Tables  │  → MinIO (S3)"
echo "│  in Metastore   │"
echo "└────────┬────────┘"
echo "         │"
echo "         ↓"
echo "┌─────────────────┐"
echo "│     Trino       │  → SQL Queries"
echo "│ localhost:8081  │"
echo "└─────────────────┘"
echo ""

echo "=========================================="
echo "Next Steps"
echo "=========================================="
echo ""
echo "1. Wait 15-20 seconds for Spark to process the batch"
echo ""
echo "2. Check if data landed in MinIO:"
echo "   docker exec trino_docker-minio-1 mc ls myminio/warehouse/analytics.logs.user_events/ --recursive"
echo ""
echo "3. Query with Trino:"
echo "   docker exec trino trino --execute \"SELECT COUNT(*) FROM local.analytics_logs.user_events;\""
echo ""
echo "4. Generate more events:"
echo "   ./run-java-demo.sh"
echo ""
echo "=========================================="
