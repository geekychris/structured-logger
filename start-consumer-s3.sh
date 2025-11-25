#!/bin/bash

# Start Spark consumer with S3/MinIO configuration
# This script should be run from the structured-logging directory

echo "Starting Spark consumer with S3/MinIO backend..."

docker exec -d spark-master bash -c "AWS_REGION=us-east-1 AWS_ACCESS_KEY_ID=admin AWS_SECRET_ACCESS_KEY=password123 nohup /opt/spark/bin/spark-submit \
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
  /opt/spark-apps/log-configs \
  kafka:29092 \
  s3a://warehouse/ \
  s3a://warehouse/checkpoints > /opt/spark-apps/consumer-s3.log 2>&1 &"

sleep 5

echo "Consumer started. Checking logs..."
docker exec spark-master tail -20 /opt/spark-apps/consumer-s3.log | grep -E "(Loaded|Processing|Started|Error)" || echo "Consumer is starting up..."

echo ""
echo "To view logs: docker exec spark-master tail -f /opt/spark-apps/consumer-s3.log"
echo "To check S3 data: docker exec minio mc ls myminio/warehouse/analytics/logs/ --recursive"
