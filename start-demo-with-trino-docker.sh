#!/bin/bash
set -e

TRINO_DOCKER_PATH="/Users/chris/code/warp_experiments/done/trino_docker"

echo "=========================================="
echo "Structured Logging Demo"
echo "Using existing trino_docker infrastructure"
echo "=========================================="
echo ""

# Check if trino_docker services are running
echo "Step 1: Checking trino_docker infrastructure..."
if ! docker ps | grep -q "kafka"; then
    echo "❌ Kafka not running. Please start trino_docker services first:"
    echo "   cd $TRINO_DOCKER_PATH"
    echo "   ./start_lakehouse.sh"
    exit 1
fi

if ! docker ps | grep -q "spark"; then
    echo "❌ Spark not running. Please start trino_docker services first:"
    echo "   cd $TRINO_DOCKER_PATH"
    echo "   ./start_lakehouse.sh"
    exit 1
fi

# Get actual container names
SPARK_MASTER=$(docker ps --format "{{.Names}}" | grep spark-master | head -1)

echo "✓ Kafka is running"
echo "✓ Spark is running"
echo "✓ Infrastructure is ready"
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

# Step 3: Copy JAR to trino_docker location
echo "Step 3: Deploying JAR to trino_docker..."
mkdir -p "$TRINO_DOCKER_PATH/spark-apps-java"
cp spark-consumer/target/structured-log-consumer-1.0.0.jar "$TRINO_DOCKER_PATH/spark-apps-java/"
echo "  ✓ JAR copied to $TRINO_DOCKER_PATH/spark-apps-java/"
echo ""

# Step 4: Ensure log configs exist
echo "Step 4: Preparing log configurations..."
if [ ! -d "log-configs" ] || [ -z "$(ls -A log-configs/*.json 2>/dev/null)" ]; then
    echo "  No log configs found. Copying examples to log-configs/..."
    mkdir -p log-configs
    cp examples/*.json log-configs/ 2>/dev/null || true
fi

CONFIG_COUNT=$(ls -1 log-configs/*.json 2>/dev/null | wc -l)
echo "  ✓ Found $CONFIG_COUNT log configuration(s)"

# Copy configs to trino_docker
mkdir -p "$TRINO_DOCKER_PATH/spark-data/log-configs"
cp log-configs/*.json "$TRINO_DOCKER_PATH/spark-data/log-configs/" 2>/dev/null || true
echo "  ✓ Configs copied to $TRINO_DOCKER_PATH/spark-data/log-configs/"
echo ""

# Step 5: Start Spark consumer
echo "Step 5: Starting Spark Streaming Consumer..."
echo "  This will:"
echo "  - Load log configurations"
echo "  - Subscribe to Kafka topics"
echo "  - Create Iceberg tables in MinIO"
echo "  - Stream logs to Iceberg tables every 10 seconds"
echo ""

# Stop any existing consumer
docker exec $SPARK_MASTER pkill -f StructuredLogConsumer 2>/dev/null || true
sleep 2

# Start consumer
docker exec -d $SPARK_MASTER bash -c "AWS_REGION=us-east-1 AWS_ACCESS_KEY_ID=admin AWS_SECRET_ACCESS_KEY=password123 /opt/spark/bin/spark-submit \
  --master 'local[*]' \
  --packages org.apache.hadoop:hadoop-aws:3.3.4,com.amazonaws:aws-java-sdk-bundle:1.12.262,software.amazon.awssdk:bundle:2.17.257,software.amazon.awssdk:url-connection-client:2.17.257 \
  --conf spark.sql.catalog.local=org.apache.iceberg.spark.SparkCatalog \
  --conf spark.sql.catalog.local.type=hive \
  --conf spark.sql.catalog.local.uri=thrift://hive-metastore:9083 \
  --conf spark.sql.catalog.local.warehouse=s3a://warehouse/ \
  --conf 'spark.sql.catalog.local.io-impl=org.apache.iceberg.aws.s3.S3FileIO' \
  --conf 'spark.sql.catalog.local.s3.endpoint=http://minio:9000' \
  --conf 'spark.sql.catalog.local.s3.path-style-access=true' \
  --conf spark.hadoop.fs.s3a.endpoint=http://minio:9000 \
  --conf spark.hadoop.fs.s3a.access.key=admin \
  --conf spark.hadoop.fs.s3a.secret.key=password123 \
  --conf spark.hadoop.fs.s3a.path.style.access=true \
  --conf 'spark.hadoop.fs.s3a.impl=org.apache.hadoop.fs.s3a.S3AFileSystem' \
  --conf spark.hadoop.fs.s3a.endpoint.region=us-east-1 \
  --conf spark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions \
  --class com.logging.consumer.StructuredLogConsumer \
  /opt/spark-apps-java/structured-log-consumer-1.0.0.jar \
  /opt/spark-data/log-configs \
  kafka:29092 \
  s3a://warehouse/ \
  /opt/spark-data/checkpoints > /opt/spark-data/consumer.log 2>&1"

sleep 5

echo "Consumer started. Checking logs..."
docker exec $SPARK_MASTER tail -20 /opt/spark-data/consumer.log 2>/dev/null | grep -E "(Loaded|Processing|Started|Error)" || echo "Consumer is starting up..."

echo ""
echo "=========================================="
echo "✅ Demo Environment Ready!"
echo "=========================================="
echo ""
echo "Services (from trino_docker):"
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
echo "     docker exec $SPARK_MASTER tail -f /opt/spark-data/consumer.log"
echo ""
echo "  3. Send test logs (Java or Python examples)"
echo ""
echo "  4. Query logs with Trino:"
echo "     docker exec -it trino trino"
echo "     SHOW SCHEMAS IN local;"
echo "     SHOW TABLES IN local.analytics_logs;"
echo ""
echo "  5. View data in MinIO:"
echo "     Open http://localhost:9001 in browser"
echo "     Browse bucket: warehouse"
echo ""
echo "To stop consumer:"
echo "  docker exec $SPARK_MASTER pkill -f StructuredLogConsumer"
echo ""
echo "=========================================="
