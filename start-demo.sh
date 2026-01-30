#!/bin/bash
set -e

echo "=========================================="
echo "Structured Logging Demo - Full Startup"
echo "=========================================="
echo ""

# Check if Docker is running
if ! docker info > /dev/null 2>&1; then
    echo "❌ Error: Docker is not running. Please start Docker first."
    exit 1
fi

# Step 1: Start Docker infrastructure
echo "Step 1: Starting Docker infrastructure..."
echo "  - MinIO (S3-compatible storage)"
echo "  - PostgreSQL (Hive Metastore backend)"
echo "  - Hive Metastore (Iceberg catalog)"
echo "  - Zookeeper"
echo "  - Kafka"
echo "  - Spark (Master & Worker)"
echo "  - Trino (SQL query engine)"
echo ""

docker-compose up -d

echo ""
echo "Waiting for services to be healthy..."
sleep 10

# Wait for Kafka to be ready
echo "Checking Kafka readiness..."
for i in {1..30}; do
    if docker exec kafka kafka-topics --bootstrap-server localhost:9092 --list > /dev/null 2>&1; then
        echo "✓ Kafka is ready"
        break
    fi
    if [ $i -eq 30 ]; then
        echo "❌ Kafka did not start in time"
        exit 1
    fi
    echo "  Waiting for Kafka... ($i/30)"
    sleep 2
done

# Wait for Hive Metastore to be ready
echo "Checking Hive Metastore readiness..."
for i in {1..30}; do
    if docker exec hive-metastore pgrep -f HiveMetaStore > /dev/null 2>&1; then
        echo "✓ Hive Metastore is ready"
        break
    fi
    if [ $i -eq 30 ]; then
        echo "❌ Hive Metastore did not start in time"
        exit 1
    fi
    echo "  Waiting for Hive Metastore... ($i/30)"
    sleep 3
done

# Wait for Spark Master to be ready
echo "Checking Spark Master readiness..."
for i in {1..20}; do
    if docker exec spark-master curl -s http://localhost:8080 > /dev/null 2>&1; then
        echo "✓ Spark Master is ready"
        break
    fi
    if [ $i -eq 20 ]; then
        echo "❌ Spark Master did not start in time"
        exit 1
    fi
    echo "  Waiting for Spark Master... ($i/20)"
    sleep 2
done

echo ""
echo "✓ All infrastructure services are running!"
echo ""

# Step 2: Build Spark consumer if needed
echo "Step 2: Checking Spark consumer JAR..."
if [ ! -f "spark-consumer/target/structured-log-consumer-1.0.0.jar" ]; then
    echo "  JAR not found. Building Spark consumer..."
    cd spark-consumer
    mvn clean package -DskipTests
    cd ..
    echo "  ✓ Build complete"
else
    echo "  ✓ JAR found: spark-consumer/target/structured-log-consumer-1.0.0.jar"
fi
echo ""

# Step 3: Ensure log configs exist
echo "Step 3: Checking log configurations..."
if [ ! -d "log-configs" ] || [ -z "$(ls -A log-configs/*.json 2>/dev/null)" ]; then
    echo "  No log configs found. Copying examples to log-configs/..."
    mkdir -p log-configs
    cp examples/*.json log-configs/ 2>/dev/null || true
fi

CONFIG_COUNT=$(ls -1 log-configs/*.json 2>/dev/null | wc -l)
echo "  ✓ Found $CONFIG_COUNT log configuration(s)"
echo ""

# Step 4: Start Spark consumer
echo "Step 4: Starting Spark Streaming Consumer..."
echo "  This will:"
echo "  - Load log configurations from log-configs/"
echo "  - Subscribe to Kafka topics"
echo "  - Create Iceberg tables in MinIO"
echo "  - Stream logs to Iceberg tables every 10 seconds"
echo ""

./start-consumer.sh

echo ""
echo "=========================================="
echo "✅ Demo Environment Ready!"
echo "=========================================="
echo ""
echo "Services:"
echo "  • Kafka:          localhost:9092"
echo "  • Zookeeper:      localhost:2181"
echo "  • MinIO Console:  http://localhost:9001 (admin/password123)"
echo "  • MinIO API:      http://localhost:9000"
echo "  • Spark Master:   http://localhost:8082"
echo "  • Trino:          http://localhost:8081"
echo "  • Hive Metastore: localhost:9083"
echo "  • PostgreSQL:     localhost:5432"
echo ""
echo "Next Steps:"
echo "  1. Generate loggers from configs:"
echo "     make generate"
echo ""
echo "  2. View Spark consumer logs:"
echo "     docker exec spark-master tail -f /opt/spark-data/consumer.log"
echo ""
echo "  3. Send test logs (Java or Python examples)"
echo ""
echo "  4. Query logs with Trino:"
echo "     docker exec -it trino trino"
echo "     SHOW CATALOGS;"
echo "     SHOW SCHEMAS IN local;"
echo "     SHOW TABLES IN local.analytics_logs;"
echo ""
echo "  5. View data in MinIO:"
echo "     Open http://localhost:9001 in browser"
echo "     Browse bucket: warehouse"
echo ""
echo "To stop everything:"
echo "  docker-compose down"
echo ""
echo "=========================================="
