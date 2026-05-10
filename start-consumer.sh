#!/bin/bash
# Start the Spark consumer(s) with S3/MinIO + Iceberg + (optional) Avro support.
#
# Usage:
#   ./start-consumer.sh                # starts kafka-to-iceberg consumer (default)
#   ./start-consumer.sh both           # starts BOTH kafka and s3 consumers
#   ./start-consumer.sh s3             # only the s3-to-iceberg consumer
#   ./start-consumer.sh kafka          # only the kafka-to-iceberg consumer (= default)
#
# Spark packages bundled here:
#   - hadoop-aws + aws-java-sdk-bundle  : s3a:// reads/writes
#   - spark-sql-kafka                   : Kafka source (approaches A, B, E)
#   - iceberg-spark-runtime             : Iceberg writes for all approaches
#   - aws sdk v2 + url-connection       : Iceberg's S3FileIO
#   - spark-avro                        : Confluent-wire Avro decoding (approach B)
#                                         AND Avro file source (approach C)

set -euo pipefail
MODE="${1:-kafka}"

case "$MODE" in
  kafka|s3|both) ;;
  *) echo "usage: $0 [kafka|s3|both]"; exit 2 ;;
esac

echo "Starting Spark consumer(s) (mode=$MODE) ..."

docker exec -u root spark-master bash -c "mkdir -p /home/spark/.ivy2/cache && chown -R spark:spark /home/spark && mkdir -p /opt/spark-data"

PACKAGES="org.apache.hadoop:hadoop-aws:3.3.4,com.amazonaws:aws-java-sdk-bundle:1.12.262,org.apache.spark:spark-sql-kafka-0-10_2.12:3.5.0,org.apache.spark:spark-avro_2.12:3.5.0,org.apache.iceberg:iceberg-spark-runtime-3.5_2.12:1.4.2,software.amazon.awssdk:bundle:2.20.18,software.amazon.awssdk:url-connection-client:2.20.18"

SPARK_CONFS="\
  --conf spark.hadoop.fs.s3a.access.key=admin \
  --conf spark.hadoop.fs.s3a.secret.key=password123 \
  --conf spark.hadoop.fs.s3a.endpoint=http://minio:9000 \
  --conf spark.hadoop.fs.s3a.path.style.access=true \
  --conf spark.hadoop.fs.s3a.region=us-east-1 \
  --conf spark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions \
  --conf spark.sql.catalog.iceberg=org.apache.iceberg.spark.SparkCatalog \
  --conf spark.sql.catalog.iceberg.type=hive \
  --conf spark.sql.catalog.iceberg.uri=thrift://hive-metastore:9083 \
  --conf spark.sql.catalog.iceberg.warehouse=s3a://warehouse/ \
  --conf spark.sql.catalog.iceberg.io-impl=org.apache.iceberg.aws.s3.S3FileIO \
  --conf spark.sql.catalog.iceberg.s3.endpoint=http://minio:9000 \
  --conf spark.sql.catalog.iceberg.s3.path-style-access=true"

ENV_PREFIX="AWS_REGION=us-east-1 AWS_ACCESS_KEY_ID=admin AWS_SECRET_ACCESS_KEY=password123"

start_kafka_consumer() {
  echo " -> kafka-to-iceberg consumer"
  docker exec -d spark-master bash -c "$ENV_PREFIX /opt/spark/bin/spark-submit \
    --name kafka-to-iceberg \
    --master 'local[*]' \
    --packages $PACKAGES \
    $SPARK_CONFS \
    /opt/spark-apps/kafka-to-iceberg-consumer.py > /opt/spark-data/consumer.log 2>&1"
}

start_s3_consumer() {
  echo " -> s3-to-iceberg consumer"
  docker exec -d spark-master bash -c "$ENV_PREFIX /opt/spark/bin/spark-submit \
    --name s3-to-iceberg \
    --master 'local[*]' \
    --packages $PACKAGES \
    $SPARK_CONFS \
    /opt/spark-apps/s3-to-iceberg-consumer.py > /opt/spark-data/s3-consumer.log 2>&1"
}

case "$MODE" in
  kafka) start_kafka_consumer ;;
  s3)    start_s3_consumer ;;
  both)  start_kafka_consumer; start_s3_consumer ;;
esac

sleep 5
echo "Consumer(s) started. Logs:"
[ "$MODE" != "s3" ]  && docker exec spark-master tail -10 /opt/spark-data/consumer.log    2>/dev/null    | grep -E "(Loaded|Processing|Started|Error|Creating|queries|namespace)" || true
[ "$MODE" != "kafka" ] && docker exec spark-master tail -10 /opt/spark-data/s3-consumer.log 2>/dev/null | grep -E "(Loaded|Processing|Started|Error|Creating|queries|namespace)" || true

echo ""
echo "Tail logs:"
[ "$MODE" != "s3" ]    && echo "  docker exec spark-master tail -f /opt/spark-data/consumer.log"
[ "$MODE" != "kafka" ] && echo "  docker exec spark-master tail -f /opt/spark-data/s3-consumer.log"
