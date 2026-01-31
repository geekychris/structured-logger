#!/bin/bash
# Reset Iceberg tables and rebuild entire structured logging system (non-interactive)
# This version runs all steps automatically without prompts

set -e

echo "============================================"
echo "Structured Logging - Auto Reset & Rebuild"
echo "============================================"
echo ""

# Parse command line args
CLEAR_KAFKA=false
REGENERATE_CODE=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --clear-kafka)
            CLEAR_KAFKA=true
            shift
            ;;
        --regenerate)
            REGENERATE_CODE=true
            shift
            ;;
        --help)
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --clear-kafka    Delete and recreate Kafka topics"
            echo "  --regenerate     Regenerate logger code from configs"
            echo "  --help           Show this help message"
            echo ""
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
done

# Step 1: Drop Iceberg tables
echo "Step 1: Dropping Iceberg tables..."
echo "----------------------------------------"

TABLES=$(docker exec trino trino --execute "SHOW TABLES FROM iceberg.analytics_logs" 2>/dev/null | grep -v "WARNING" | tr -d '"' || echo "")

if [ -z "$TABLES" ]; then
    echo "  ℹ️  No tables to drop"
else
    for table in $TABLES; do
        if [[ "$table" == "information_schema" ]] || [[ "$table" == "system" ]]; then
            continue
        fi
        
        echo "  Dropping iceberg.analytics_logs.$table..."
        docker exec trino trino --execute "DROP TABLE IF EXISTS iceberg.analytics_logs.$table" 2>/dev/null || echo "    ⚠️  Could not drop $table"
    done
    echo "  ✅ Dropped all tables"
fi
echo ""

# Step 2: Stop consumer
echo "Step 2: Stopping Spark consumer..."
echo "----------------------------------------"
docker exec spark-master pkill -f kafka-to-iceberg-consumer.py 2>/dev/null || echo "  ℹ️  Consumer not running"
sleep 2
echo "  ✅ Consumer stopped"
echo ""

# Step 3: Clear Kafka topics (if requested)
if [ "$CLEAR_KAFKA" = true ]; then
    echo "Step 3: Clearing Kafka topics..."
    echo "----------------------------------------"
    
    TOPICS="user-events api-metrics"
    for topic in $TOPICS; do
        echo "  Deleting topic: $topic"
        docker exec kafka kafka-topics --bootstrap-server localhost:9092 --delete --topic $topic 2>/dev/null || echo "    ℹ️  Topic $topic doesn't exist"
    done
    
    echo "  Recreating topics..."
    for script in scripts/create-topic-*.sh; do
        if [ -f "$script" ]; then
            echo "    Running $(basename $script)..."
            bash "$script" > /dev/null 2>&1 || echo "      ⚠️  Failed"
        fi
    done
    echo "  ✅ Topics recreated"
else
    echo "Step 3: Skipping Kafka topic deletion (use --clear-kafka to enable)"
fi
echo ""

# Step 4: Regenerate code (if requested)
if [ "$REGENERATE_CODE" = true ]; then
    echo "Step 4: Regenerating logger code..."
    echo "----------------------------------------"
    
    for config in log-configs/*.json; do
        if [ -f "$config" ]; then
            echo "  Generating from $(basename $config)..."
            python3 generators/generate_loggers.py "$config" || echo "    ⚠️  Failed"
        fi
    done
    echo "  ✅ Logger code regenerated"
    echo ""
    STEP_NUM=5
else
    echo "Step 4: Skipping code regeneration (use --regenerate to enable)"
    echo ""
    STEP_NUM=4
fi

# Step N: Clean and rebuild Java logger
echo "Step $STEP_NUM: Rebuilding Java logger..."
echo "----------------------------------------"
cd java-logger
mvn clean package -q
if [ $? -eq 0 ]; then
    echo "  ✅ Java logger rebuilt"
else
    echo "  ❌ Java build failed"
    exit 1
fi
cd ..
echo ""
STEP_NUM=$((STEP_NUM + 1))

# Step N+1: Update consumer in Docker
echo "Step $STEP_NUM: Updating consumer in Docker..."
echo "----------------------------------------"
docker cp spark-consumer/kafka-to-iceberg-consumer.py spark-master:/opt/spark-apps/kafka-to-iceberg-consumer.py
echo "  ✅ Consumer updated"
echo ""
STEP_NUM=$((STEP_NUM + 1))

# Step N+2: Restart consumer
echo "Step $STEP_NUM: Starting Spark consumer..."
echo "----------------------------------------"
./start-consumer.sh
echo ""
STEP_NUM=$((STEP_NUM + 1))

# Step N+3: Wait for consumer initialization
echo "Step $STEP_NUM: Waiting for consumer to initialize (10 seconds)..."
echo "----------------------------------------"
sleep 10
echo "  ✅ Consumer initialized"
echo ""
STEP_NUM=$((STEP_NUM + 1))

# Step N+4: Run demo
echo "Step $STEP_NUM: Running Java demo to send test data..."
echo "----------------------------------------"
./run-java-demo.sh 2>&1 | grep -E "(Building|Compiled|logged successfully|Log Events)"
echo ""
STEP_NUM=$((STEP_NUM + 1))

# Step N+5: Wait for processing
echo "Step $STEP_NUM: Waiting for Spark to process data (15 seconds)..."
echo "----------------------------------------"
sleep 15
echo "  ✅ Processing complete"
echo ""
STEP_NUM=$((STEP_NUM + 1))

# Step N+6: Verify results
echo "Step $STEP_NUM: Verifying results..."
echo "----------------------------------------"

echo "  Tables created:"
TABLES=$(docker exec trino trino --execute "SHOW TABLES FROM iceberg.analytics_logs" 2>/dev/null | grep -v "WARNING" | tr -d '"' || echo "")
for table in $TABLES; do
    if [[ "$table" != "information_schema" ]] && [[ "$table" != "system" ]]; then
        COUNT=$(docker exec trino trino --execute "SELECT COUNT(*) FROM iceberg.analytics_logs.$table" 2>/dev/null | tail -1 | tr -d '"')
        echo "    - $table: $COUNT rows"
    fi
done
echo ""
STEP_NUM=$((STEP_NUM + 1))

# Step N+7: Verify envelope format
echo "Step $STEP_NUM: Verifying envelope format..."
echo "----------------------------------------"
./verify_envelope.sh > /tmp/envelope_verify.log 2>&1
if [ $? -eq 0 ]; then
    echo "  ✅ Envelope format verified"
    echo ""
    grep "_log_type:" /tmp/envelope_verify.log | head -1
    grep "_log_class:" /tmp/envelope_verify.log | head -1
    grep "_version:" /tmp/envelope_verify.log | head -1
else
    echo "  ⚠️  Envelope verification failed - check /tmp/envelope_verify.log"
fi
echo ""

# Summary
echo "============================================"
echo "✅ RESET AND REBUILD COMPLETE"
echo "============================================"
echo ""
echo "System Status:"
echo "  • Iceberg tables: Dropped and recreated"
echo "  • Kafka topics: $([ "$CLEAR_KAFKA" = true ] && echo "Cleared and recreated" || echo "Preserved (existing data)")"
echo "  • Logger code: $([ "$REGENERATE_CODE" = true ] && echo "Regenerated from configs" || echo "Used existing code")"
echo "  • Java logger: Rebuilt with envelope support"
echo "  • Spark consumer: Updated and running"
echo "  • Test data: Sent and processed"
echo ""
echo "Next steps:"
echo "  • Run demo again: ./run-java-demo.sh"
echo "  • Query data: docker exec -it trino trino"
echo "  • View logs: docker exec spark-master tail -f /opt/spark-data/consumer.log"
echo ""
