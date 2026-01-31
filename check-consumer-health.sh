#!/bin/bash
# Consumer health check script

echo "=== Consumer Health Check ==="
echo ""

# Check if running
echo "1. Process Status:"
if docker exec spark-master ps aux 2>/dev/null | grep -q kafka-to-iceberg; then
    echo "  ✓ Consumer is running"
    PID=$(docker exec spark-master ps aux | grep kafka-to-iceberg | grep -v grep | awk '{print $2}' | head -1)
    echo "    PID: $PID"
else
    echo "  ✗ Consumer is NOT running"
fi
echo ""

# Check recent activity
echo "2. Recent Activity (last 10 lines, excluding idle messages):"
docker exec spark-master tail -10 /opt/spark-data/consumer.log 2>/dev/null | grep -v "idle" || echo "  No log file found"
echo ""

# Check for errors
echo "3. Recent Errors:"
ERROR_COUNT=$(docker exec spark-master grep -c -i "error\|exception" /opt/spark-data/consumer.log 2>/dev/null || echo 0)
if [ "$ERROR_COUNT" -gt 0 ]; then
    echo "  ⚠️  Found $ERROR_COUNT errors/exceptions"
    echo "  Last 3 errors:"
    docker exec spark-master grep -i "error\|exception" /opt/spark-data/consumer.log 2>/dev/null | tail -3 | sed 's/^/    /'
else
    echo "  ✓ No errors found"
fi
echo ""

# Check Kafka lag
echo "4. Kafka Messages:"
for topic in user-events api-metrics; do
    OFFSET=$(docker exec kafka kafka-run-class kafka.tools.GetOffsetShell \
      --broker-list localhost:9092 --topic $topic --time -1 2>/dev/null | cut -d: -f3)
    if [ -z "$OFFSET" ]; then
        echo "  $topic: Topic not found or empty"
    else
        echo "  $topic: $OFFSET messages total"
    fi
done
echo ""

# Check Iceberg tables
echo "5. Iceberg Row Counts:"
RESULT=$(docker exec trino trino --execute "
  SELECT 'user_events' as table_name, CAST(COUNT(*) AS VARCHAR) as row_count FROM iceberg.analytics_logs.user_events
  UNION ALL
  SELECT 'api_metrics', CAST(COUNT(*) AS VARCHAR) FROM iceberg.analytics_logs.api_metrics
" 2>/dev/null | grep -v WARNING | tr -d '"')

if [ -z "$RESULT" ]; then
    echo "  ⚠️  Could not query tables (may not exist yet)"
else
    echo "$RESULT" | while read line; do
        echo "    $line"
    done
fi
echo ""

# Summary
echo "=== Summary ==="
if docker exec spark-master ps aux 2>/dev/null | grep -q kafka-to-iceberg && [ "$ERROR_COUNT" -eq 0 ]; then
    echo "✅ Consumer is healthy"
else
    echo "⚠️  Consumer may have issues - check details above"
fi
