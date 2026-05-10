#!/usr/bin/env python3
"""
S3 to Iceberg Consumer.

Companion to kafka-to-iceberg-consumer.py for the sidecar→S3 transport
(approaches C and D in the bench). For each log config whose
`transport.sinks` includes `"s3"`, this consumer:

  * watches the configured S3 bucket via Spark's file streaming source
  * reads the Avro / Parquet objects produced by the sidecar
  * unwraps the LogEnvelope, extracts the data record
  * writes to the same Iceberg table as the Kafka consumer would

The Iceberg table layout matches kafka-to-iceberg-consumer.py exactly so a
log type can switch transports without changing the warehouse-side query
surface.

Spark packages required (added by start-consumer.sh):
  - org.apache.spark:spark-avro_2.12:<spark-version>   (for Avro reads)
"""

import json
import os
import sys
from pyspark.sql import SparkSession
from pyspark.sql.functions import col, current_timestamp, to_timestamp
from pyspark.sql.types import (
    StructType, StructField, StringType, IntegerType,
    LongType, DoubleType, TimestampType, DateType, BooleanType, FloatType,
    ArrayType, MapType,
)

CONFIG_DIR = os.environ.get("LOG_CONFIG_DIR", "/opt/spark-apps/log-configs")
CHECKPOINT_DIR = os.environ.get("CHECKPOINT_DIR", "/opt/spark-data/checkpoints/s3")


_TYPE_MAP = {
    "string": StringType(),
    "int": IntegerType(),
    "integer": IntegerType(),
    "long": LongType(),
    "double": DoubleType(),
    "float": FloatType(),
    "boolean": BooleanType(),
    "timestamp": TimestampType(),
    "date": DateType(),
    "array<string>": ArrayType(StringType()),
    "array<int>": ArrayType(IntegerType()),
    "array<long>": ArrayType(LongType()),
    "map<string,string>": MapType(StringType(), StringType()),
}


def _spark_schema_from_fields(fields):
    return StructType([
        StructField(f["name"], _TYPE_MAP.get(f["type"], StringType()), True)
        for f in fields
    ])


def _create_iceberg_table_if_not_exists(spark, table_name, schema, partition_fields):
    try:
        spark.sql(f"DESCRIBE TABLE iceberg.analytics_logs.{table_name}")
        print(f"Table iceberg.analytics_logs.{table_name} already exists", flush=True)
        return
    except Exception:
        pass
    fields_ddl = ", ".join(
        f"`{f.name}` {f.dataType.simpleString()}" for f in schema.fields
    )
    partition_clause = ""
    if partition_fields:
        partition_clause = f"PARTITIONED BY ({', '.join(partition_fields)})"
    spark.sql(f"""
        CREATE TABLE IF NOT EXISTS iceberg.analytics_logs.{table_name} (
            {fields_ddl}
        )
        USING iceberg
        {partition_clause}
        TBLPROPERTIES (
            'format-version' = '2',
            'write.parquet.compression-codec' = 'snappy'
        )
    """)
    print(f"Created table iceberg.analytics_logs.{table_name}", flush=True)


def _resolve_bucket_url(s3_cfg):
    """Build the s3a:// URL for Spark to read from. Honors path-style + endpoint
    via Spark hadoop config (set in start-consumer.sh), so we just need the
    bucket name + optional key prefix."""
    bucket = s3_cfg.get("bucket")
    if not bucket:
        raise ValueError("transport.s3.bucket is required for the S3 consumer")
    prefix = (s3_cfg.get("key_prefix") or "").strip("/")
    base = f"s3a://{bucket}/"
    if prefix:
        base = base + prefix + "/"
    return base


def process_s3_source(spark, config):
    """Wire one log config's S3 sink into Iceberg."""
    transport = config.get("transport") or {}
    sinks = [s.lower() for s in (transport.get("sinks") or [])]
    if "s3" not in sinks:
        return None
    s3_cfg = transport.get("s3") or {}
    encoding = (s3_cfg.get("encoding") or "parquet").lower()
    if encoding not in ("avro", "parquet"):
        raise ValueError(f"unsupported s3.encoding={encoding}")

    bucket_url = _resolve_bucket_url(s3_cfg)
    full_table_name = config.get("warehouse", {}).get("table_name", f"analytics_logs.{config['name'].lower()}")
    table_name = full_table_name.split(".")[-1]
    data_schema = _spark_schema_from_fields(config["fields"])
    partition_fields = config.get("iceberg", {}).get("partition_fields", [])

    print(f"\n=== S3 source: {bucket_url} (encoding={encoding}) -> table {table_name} ===", flush=True)
    _create_iceberg_table_if_not_exists(spark, table_name, data_schema, partition_fields)

    envelope_schema = StructType([
        StructField("_log_type", StringType(), False),
        StructField("_log_class", StringType(), False),
        StructField("_version", StringType(), False),
        StructField("data", data_schema, False),
    ])

    if encoding == "avro":
        # Spark's avro source reads Avro object container files; envelope is a
        # nested record. We pass the envelope schema explicitly so older files
        # with extra fields don't trip schema-evolution behaviour.
        df = (
            spark.readStream
            .format("avro")
            .schema(envelope_schema)
            .option("recursiveFileLookup", "true")
            .load(bucket_url)
        )
        parsed_df = df.select("data.*")
    else:  # parquet
        # The Python S3BatchSink writes a flattened parquet with columns:
        #   _log_type, _log_class, _version, data_json (string), key
        # Decode data_json back to the data schema so the downstream Iceberg
        # write looks identical to the Avro / Kafka paths.
        flat_schema = StructType([
            StructField("_log_type", StringType(), False),
            StructField("_log_class", StringType(), False),
            StructField("_version", StringType(), False),
            StructField("data_json", StringType(), False),
            StructField("key", StringType(), True),
        ])
        from pyspark.sql.functions import from_json
        df = (
            spark.readStream
            .format("parquet")
            .schema(flat_schema)
            .option("recursiveFileLookup", "true")
            .load(bucket_url)
        )
        parsed_df = df.select(
            from_json(col("data_json"), data_schema).alias("data")
        ).select("data.*")

    # Coerce timestamp/date strings to native types (sidecar writes them as ISO strings)
    for f in data_schema.fields:
        if isinstance(f.dataType, TimestampType):
            parsed_df = parsed_df.withColumn(f.name, to_timestamp(col(f.name)))

    # NOTE: kafka-to-iceberg-consumer doesn't add _ingestion_timestamp, so we
    # don't either — keeps the table schema identical regardless of transport.
    # If you want ingestion-timestamp tracking, add it to BOTH consumers AND
    # to the create_table DDL.

    checkpoint_path = f"{CHECKPOINT_DIR}/{table_name}"
    query = (
        parsed_df.writeStream
        .format("iceberg")
        .outputMode("append")
        .option("checkpointLocation", checkpoint_path)
        .option("path", f"iceberg.analytics_logs.{table_name}")
        .option("fanout-enabled", "true")
        # Tunable: how often Spark scans for new objects. Lower = fresher data,
        # higher S3 LIST cost. Default 30s matches the bench's sidecar rotation.
        .trigger(processingTime=os.environ.get("S3_TRIGGER_INTERVAL", "30 seconds"))
        .start()
    )
    print(f"Started s3 streaming query for {table_name} (trigger={os.environ.get('S3_TRIGGER_INTERVAL', '30 seconds')})",
          flush=True)
    return query


def main():
    print("Starting S3 to Iceberg Consumer...", flush=True)
    spark = (
        SparkSession.builder
        .appName("S3ToIcebergConsumer")
        .getOrCreate()
    )
    spark.sparkContext.setLogLevel("WARN")
    try:
        spark.sql("CREATE NAMESPACE IF NOT EXISTS iceberg.analytics_logs")
        print("Created/verified namespace iceberg.analytics_logs", flush=True)
    except Exception as e:
        print(f"Namespace create note: {e}", flush=True)

    queries = []
    for filename in sorted(os.listdir(CONFIG_DIR)):
        if not filename.endswith(".json"):
            continue
        path = os.path.join(CONFIG_DIR, filename)
        with open(path) as f:
            config = json.load(f)
        try:
            q = process_s3_source(spark, config)
            if q is not None:
                queries.append(q)
        except Exception as e:
            print(f"Error processing {filename}: {e}", flush=True)
            import traceback
            traceback.print_exc()

    if not queries:
        print("No log configs use the s3 sink — exiting (this is fine if you only use Kafka).",
              flush=True)
        return

    print(f"\n=== Started {len(queries)} S3 streaming queries ===", flush=True)
    for q in queries:
        q.awaitTermination()


if __name__ == "__main__":
    sys.exit(main())
