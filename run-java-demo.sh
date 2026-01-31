#!/bin/bash

echo "=========================================="
echo "Running Java Structured Logging Demo"
echo "=========================================="
echo ""

# Check if Kafka is running
if ! docker ps | grep -q kafka; then
    echo "❌ Kafka is not running!"
    echo "Please start infrastructure first:"
    echo "  ./start-demo-with-trino-docker.sh"
    exit 1
fi

echo "✓ Infrastructure is running"
echo ""

# Build Java logger with dependencies
echo "Building Java logger..."
cd java-logger
mvn clean package -q

if [ $? -ne 0 ]; then
    echo "❌ Maven build failed"
    exit 1
fi

cd ..
echo "✓ Built successfully"
echo ""

# Build classpath with all dependencies
CLASSPATH="$(pwd)/java-logger/target/structured-logging-java-1.0.0.jar"
for jar in $(pwd)/java-logger/target/lib/*.jar; do
    CLASSPATH="$CLASSPATH:$jar"
done

echo "Building and running JavaExample..."
echo ""

cd examples

# Compile the example
javac -cp "$CLASSPATH" JavaExample.java

if [ $? -ne 0 ]; then
    echo "❌ Compilation failed"
    exit 1
fi

echo "✓ Compiled successfully"
echo ""
echo "Sending log events to Kafka..."
echo ""

# Run the example
java -cp "$CLASSPATH:." JavaExample

if [ $? -ne 0 ]; then
    echo "❌ Execution failed"
    exit 1
fi

echo ""
echo "=========================================="
echo "✅ Log Events Sent!"
echo "=========================================="
echo ""
echo "Your log events are now flowing through:"
echo "  1. Kafka topic → kafka:29092"
echo "  2. Spark Consumer → Processing in real-time"
echo "  3. Iceberg Tables → Stored in MinIO"
echo ""
echo "Next steps:"
echo ""
echo "  1. Wait ~15 seconds for Spark to process the batch"
echo ""
echo "  2. Check consumer logs:"
echo "     docker exec trino_docker-spark-master-1 tail -30 /opt/spark-data/consumer.log"
echo ""
echo "  3. Query the data with Trino:"
echo "     docker exec -it trino trino"
echo "     USE local.analytics_logs;"
echo "     SELECT * FROM user_events ORDER BY timestamp DESC LIMIT 5;"
echo "     SELECT * FROM api_metrics ORDER BY timestamp DESC LIMIT 5;"
echo ""
echo "  4. Run this script again to send more events!"
echo ""
echo "=========================================="
