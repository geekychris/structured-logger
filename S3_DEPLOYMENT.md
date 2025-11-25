# S3/MinIO Deployment Guide

This guide explains how to run the Structured Logging System with S3-compatible storage (MinIO) for Iceberg tables.

## Architecture

The system uses:
- **Kafka** for streaming log events
- **Spark Streaming** for consuming and processing logs
- **Iceberg** for managing table metadata and schema evolution
- **MinIO/S3** for storing Parquet data files and Iceberg metadata
- **Hive Metastore** for catalog metadata (table locations, schemas)

## S3/MinIO Configuration

### Why S3 Storage?

Using S3/MinIO provides:
- **Scalability**: Store unlimited data without local disk constraints
- **Durability**: Built-in replication and data protection
- **Cost-effectiveness**: Cheaper than block storage for large datasets
- **Cloud-ready**: Same interface works with AWS S3, MinIO, or other S3-compatible stores

### Configuration Components

The S3 integration requires configuring three layers:

#### 1. S3A FileSystem (Hadoop)
For Spark to access S3:
```bash
--conf spark.hadoop.fs.s3a.endpoint=http://minio:9000
--conf spark.hadoop.fs.s3a.access.key=admin
--conf spark.hadoop.fs.s3a.secret.key=password123
--conf spark.hadoop.fs.s3a.path.style.access=true
--conf spark.hadoop.fs.s3a.impl=org.apache.hadoop.fs.s3a.S3AFileSystem
--conf spark.hadoop.fs.s3a.endpoint.region=us-east-1
```

#### 2. Iceberg S3FileIO
For Iceberg to write data and metadata:
```bash
--conf spark.sql.catalog.local.warehouse=s3a://warehouse/
--conf spark.sql.catalog.local.io-impl=org.apache.iceberg.aws.s3.S3FileIO
--conf spark.sql.catalog.local.s3.endpoint=http://minio:9000
--conf spark.sql.catalog.local.s3.path-style-access=true
```

#### 3. AWS SDK Credentials
Required by Iceberg's S3FileIO (AWS SDK v2):
```bash
AWS_REGION=us-east-1
AWS_ACCESS_KEY_ID=admin
AWS_SECRET_ACCESS_KEY=password123
```

### Required Dependencies

The Spark consumer needs these packages:
```bash
--packages org.apache.hadoop:hadoop-aws:3.3.4,\
com.amazonaws:aws-java-sdk-bundle:1.12.262,\
software.amazon.awssdk:bundle:2.17.257,\
software.amazon.awssdk:url-connection-client:2.17.257
```

## Running with S3

### Quick Start

1. **Build the consumer**:
   ```bash
   cd spark-consumer
   mvn clean package -DskipTests
   cp target/structured-log-consumer-1.0.0.jar ../../../spark-apps-java/
   ```

2. **Start the consumer**:
   ```bash
   cd ..
   ./start-consumer-s3.sh
   ```

3. **Verify deployment**:
   ```bash
   # Check consumer logs
   docker exec spark-master tail -f /opt/spark-apps/consumer-s3.log
   
   # Check S3 data
   docker exec minio mc ls myminio/warehouse/analytics/logs/ --recursive
   ```

### Manual Start

For more control, start manually:

```bash
docker exec -d spark-master bash -c "AWS_REGION=us-east-1 AWS_ACCESS_KEY_ID=admin AWS_SECRET_ACCESS_KEY=password123 nohup /opt/spark/bin/spark-submit \
  --master 'local[*]' \
  --packages org.apache.hadoop:hadoop-aws:3.3.4,com.amazonaws:aws-java-sdk-bundle:1.12.262,software.amazon.awssdk:bundle:2.17.257,software.amazon.awssdk:url-connection-client:2.17.257 \
  --conf spark.sql.catalog.local=org.apache.iceberg.spark.SparkCatalog \
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
```

## Testing

### Send Test Messages

```bash
cd examples
python3 python_example.py
```

### Verify Data in S3

```bash
# List all tables
docker exec minio mc ls myminio/warehouse/analytics/logs/

# List data files for user_events
docker exec minio mc ls myminio/warehouse/analytics/logs/user_events/ --recursive

# List data files for api_metrics
docker exec minio mc ls myminio/warehouse/analytics/logs/api_metrics/ --recursive
```

### Expected S3 Structure

```
warehouse/
├── analytics/
│   └── logs/
│       ├── user_events/
│       │   ├── metadata/
│       │   │   ├── v1.gz.metadata.json
│       │   │   ├── v2.gz.metadata.json
│       │   │   ├── snap-*.avro
│       │   │   └── version-hint.text
│       │   └── data/
│       │       └── event_date=2025-11-25/
│       │           ├── event_type=click/
│       │           │   └── *.parquet
│       │           ├── event_type=page_view/
│       │           │   └── *.parquet
│       │           └── event_type=purchase/
│       │               └── *.parquet
│       └── api_metrics/
│           ├── metadata/
│           │   └── ... (similar to user_events)
│           └── data/
│               └── metric_date=2025-11-25/
│                   ├── service_name=user-service/
│                   │   └── *.parquet
│                   ├── service_name=search-service/
│                   │   └── *.parquet
│                   └── service_name=payment-service/
│                       └── *.parquet
└── checkpoints/
    ├── UserEvents/
    │   ├── metadata
    │   ├── offsets/
    │   ├── commits/
    │   └── sources/
    └── ApiMetrics/
        └── ... (similar to UserEvents)
```

## MinIO Console

Access the MinIO web console to browse files:

1. Open http://localhost:9001
2. Login: `admin` / `password123`
3. Navigate to the `warehouse` bucket

## Troubleshooting

### Consumer Not Starting

Check logs:
```bash
docker exec spark-master tail -100 /opt/spark-apps/consumer-s3.log
```

Common issues:
- **Missing AWS credentials**: Ensure `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` env vars are set
- **Missing AWS region**: Ensure `AWS_REGION=us-east-1` is set
- **Missing S3A packages**: Verify `hadoop-aws` and `aws-java-sdk-bundle` are included in `--packages`
- **MinIO not accessible**: Ensure `minio` container is running and healthy

### No Data Files in S3

1. Check if consumer is running:
   ```bash
   docker exec spark-master ps aux | grep StructuredLogConsumer
   ```

2. Verify Kafka has messages:
   ```bash
   docker exec kafka kafka-console-consumer --bootstrap-server localhost:9092 --topic user-events --from-beginning --max-messages 5
   ```

3. Check consumer is processing:
   ```bash
   docker exec spark-master tail -50 /opt/spark-apps/consumer-s3.log | grep "Started streaming"
   ```

### Region Errors

If you see "Unable to load region" errors, ensure:
- `AWS_REGION` environment variable is set when starting spark-submit
- Region is valid (use `us-east-1` for MinIO)

### Path Style Access Errors

If you see "bucket not found" or 403 errors:
- Ensure `spark.hadoop.fs.s3a.path.style.access=true`
- Ensure `spark.sql.catalog.local.s3.path-style-access=true`
- MinIO requires path-style access (not virtual-hosted style)

## Production Considerations

For production deployment with real AWS S3:

1. **Use IAM roles** instead of access keys:
   ```bash
   # Remove AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY
   # Configure IAM role for EC2/ECS/EKS
   ```

2. **Set correct region**:
   ```bash
   AWS_REGION=us-west-2  # Your actual AWS region
   ```

3. **Remove endpoint override**:
   ```bash
   # Remove these lines:
   # --conf spark.hadoop.fs.s3a.endpoint=http://minio:9000
   # --conf spark.sql.catalog.local.s3.endpoint=http://minio:9000
   ```

4. **Disable path-style access** (AWS S3 default):
   ```bash
   # Remove or set to false:
   # --conf spark.hadoop.fs.s3a.path.style.access=false
   # --conf spark.sql.catalog.local.s3.path-style-access=false
   ```

5. **Use encryption**:
   ```bash
   --conf spark.hadoop.fs.s3a.server-side-encryption-algorithm=AES256
   ```

6. **Configure bucket**:
   ```bash
   --conf spark.sql.catalog.local.warehouse=s3a://your-production-bucket/warehouse/
   ```

## Performance Tuning

### S3A Configuration

For better performance:

```bash
# Connection pooling
--conf spark.hadoop.fs.s3a.connection.maximum=50

# Multipart upload
--conf spark.hadoop.fs.s3a.multipart.size=104857600  # 100MB

# Fast upload
--conf spark.hadoop.fs.s3a.fast.upload=true
--conf spark.hadoop.fs.s3a.fast.upload.buffer=bytebuffer
```

### Iceberg Configuration

```bash
# Larger file sizes
--conf spark.sql.catalog.local.write.target-file-size-bytes=536870912  # 512MB

# Parquet compression
--conf spark.sql.catalog.local.write.parquet.compression-codec=zstd

# Metadata compression
--conf spark.sql.catalog.local.write.metadata.compression-codec=gzip
```

## Monitoring

Key metrics to watch:
- Streaming query progress (in Spark logs)
- S3 bucket size growth
- Number of Parquet files
- Iceberg snapshot count
- Kafka consumer lag

Check streaming query metrics:
```bash
docker exec spark-master tail -f /opt/spark-apps/consumer-s3.log | grep "Streaming query made progress"
```
