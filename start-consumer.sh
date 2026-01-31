#!/bin/bash

# Start Spark consumer with S3/MinIO configuration
# This script should be run from the structured-logging directory

echo "Starting Spark consumer with S3/MinIO backend..."

# Ensure directories exist
docker exec -u root spark-master bash -c "mkdir -p /home/spark/.ivy2/cache && chown -R spark:spark /home/spark && mkdir -p /opt/spark-data"

# Start the Python consumer
docker exec -d spark-master bash -c "/opt/spark/bin/spark-submit \
  --master 'local[*]' \
  --packages org.apache.hadoop:hadoop-aws:3.3.4,com.amazonaws:aws-java-sdk-bundle:1.12.262,org.apache.spark:spark-sql-kafka-0-10_2.12:3.5.0,org.apache.iceberg:iceberg-spark-runtime-3.5_2.12:1.4.2,software.amazon.awssdk:bundle:2.20.18,software.amazon.awssdk:url-connection-client:2.20.18 \
  --conf spark.hadoop.fs.s3a.access.key=admin \
  --conf spark.hadoop.fs.s3a.secret.key=password123 \
  --conf spark.hadoop.fs.s3a.endpoint=http://minio:9000 \
  --conf spark.hadoop.fs.s3a.path.style.access=true \
  --conf spark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions \
  --conf spark.sql.catalog.iceberg=org.apache.iceberg.spark.SparkCatalog \
  --conf spark.sql.catalog.iceberg.type=hive \
  --conf spark.sql.catalog.iceberg.uri=thrift://hive-metastore:9083 \
  --conf spark.sql.catalog.iceberg.warehouse=s3a://warehouse/ \
  --conf spark.sql.catalog.iceberg.io-impl=org.apache.iceberg.aws.s3.S3FileIO \
  --conf spark.sql.catalog.iceberg.s3.endpoint=http://minio:9000 \
  --conf spark.sql.catalog.iceberg.s3.path-style-access=true \
  /opt/spark-apps/kafka-to-iceberg-consumer.py > /opt/spark-data/consumer.log 2>&1"

sleep 5

echo "Consumer started. Checking logs..."
docker exec spark-master tail -20 /opt/spark-data/consumer.log | grep -E "(Loaded|Processing|Started|Error|Creating|queries)" || echo "Consumer is starting up..."

echo ""
echo "To view logs: docker exec spark-master tail -f /opt/spark-data/consumer.log"
echo "To check S3 data: docker exec spark-master ls -la /opt/spark-data/"
