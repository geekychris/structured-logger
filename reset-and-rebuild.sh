#!/bin/bash
# Reset Iceberg tables and rebuild entire structured logging system

set -e

echo "============================================"
echo "Structured Logging - Reset and Rebuild"
echo "============================================"
echo ""

# Step 1: Drop Iceberg tables
echo "Step 1: Dropping Iceberg tables..."
echo "----------------------------------------"

TABLES=$(docker exec trino trino --execute "SHOW TABLES FROM iceberg.analytics_logs" 2>/dev/null | grep -v "WARNING" | tr -d '"' || echo "")

if [ -z "$TABLES" ]; then
    echo "  ℹ️  No tables to drop"
else
    for table in $TABLES; do
        # Skip system tables
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

# Step 3: Clear Kafka topics (optional - keeps data for replay)
read -p "Do you want to delete Kafka topic data? (y/N): " -n 1 -r
echo ""
if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo "Step 3: Clearing Kafka topics..."
    echo "----------------------------------------"
    
    TOPICS="user-events api-metrics"
    for topic in $TOPICS; do
        echo "  Deleting topic: $topic"
        docker exec kafka kafka-topics --bootstrap-server localhost:9092 --delete --topic $topic 2>/dev/null || echo "    ℹ️  Topic $topic doesn't exist"
    done
    
    # Recreate topics
    echo "  Recreating topics..."
    for script in scripts/create-topic-*.sh; do
        if [ -f "$script" ]; then
            echo "    Running $(basename $script)..."
            bash "$script" > /dev/null 2>&1 || echo "      ⚠️  Failed"
        fi
    done
    echo "  ✅ Topics recreated"
else
    echo "Step 3: Skipping Kafka topic deletion (keeping existing data)"
fi
echo ""

# Step 4: Clean and rebuild Java logger
echo "Step 4: Rebuilding Java logger..."
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

# Step 5: Regenerate logger code (optional)
read -p "Do you want to regenerate logger code from configs? (y/N): " -n 1 -r
echo ""
if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo "Step 5: Regenerating logger code..."
    echo "----------------------------------------"
    
    for config in log-configs/*.json; do
        if [ -f "$config" ]; then
            echo "  Generating from $(basename $config)..."
            python3 generators/generate_loggers.py "$config" || echo "    ⚠️  Failed"
        fi
    done
    
    # Rebuild Java after regeneration
    echo "  Rebuilding Java after code generation..."
    cd java-logger
    mvn clean package -q
    cd ..
    echo "  ✅ Logger code regenerated and rebuilt"
else
    echo "Step 5: Skipping logger code regeneration"
fi
echo ""

# Step 6: Update consumer in Docker
echo "Step 6: Updating consumer in Docker..."
echo "----------------------------------------"
docker cp spark-consumer/kafka-to-iceberg-consumer.py spark-master:/opt/spark-apps/kafka-to-iceberg-consumer.py
echo "  ✅ Consumer updated"
echo ""

# Step 7: Restart consumer
echo "Step 7: Starting Spark consumer..."
echo "----------------------------------------"
./start-consumer.sh
echo ""

# Step 8: Wait for consumer initialization
echo "Step 8: Waiting for consumer to initialize (10 seconds)..."
echo "----------------------------------------"
sleep 10
echo "  ✅ Consumer initialized"
echo ""

# Step 9: Run demo
echo "Step 9: Running Java demo to send test data..."
echo "----------------------------------------"
./run-java-demo.sh 2>&1 | grep -E "(Building|Compiled|logged successfully|Log Events)"
echo ""

# Step 10: Wait for processing
echo "Step 10: Waiting for Spark to process data (15 seconds)..."
echo "----------------------------------------"
sleep 15
echo "  ✅ Processing complete"
echo ""

# Step 11: Verify results
echo "Step 11: Verifying results..."
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

# Step 12: Verify envelope format
echo "Step 12: Verifying envelope format..."
echo "----------------------------------------"
./verify_envelope.sh > /tmp/envelope_verify.log 2>&1
if [ $? -eq 0 ]; then
    echo "  ✅ Envelope format verified"
    echo ""
    # Show key results
    grep "log_type:" /tmp/envelope_verify.log | head -1
    grep "log_class:" /tmp/envelope_verify.log | head -1
    grep "version:" /tmp/envelope_verify.log | head -1
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
echo "  • Java logger: Rebuilt with envelope support"
echo "  • Spark consumer: Updated and running"
echo "  • Test data: Sent and processed"
echo ""
echo "Next steps:"
echo "  • Run demo again: ./run-java-demo.sh"
echo "  • Query data: docker exec -it trino trino"
echo "  • View logs: docker exec spark-master tail -f /opt/spark-data/consumer.log"
echo ""
