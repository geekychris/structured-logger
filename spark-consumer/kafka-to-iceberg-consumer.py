#!/usr/bin/env python3
"""
Kafka to Iceberg Consumer
Reads log messages from Kafka topics and writes them to Iceberg tables.
"""

import json
import os
from pyspark.sql import SparkSession
from pyspark.sql.functions import col, from_json, struct, to_timestamp
from pyspark.sql.types import StructType, StructField, StringType, IntegerType, LongType, DoubleType, TimestampType

# Configuration directory
CONFIG_DIR = "/opt/spark-apps/log-configs"

def get_schema_for_config(config):
    """Convert config fields to Spark StructType"""
    fields = []
    type_mapping = {
        "string": StringType(),
        "int": IntegerType(),
        "integer": IntegerType(),
        "long": LongType(),
        "double": DoubleType(),
        "timestamp": TimestampType()
    }
    
    for field in config["fields"]:
        field_name = field["name"]
        field_type = field["type"]
        spark_type = type_mapping.get(field_type, StringType())
        fields.append(StructField(field_name, spark_type, True))
    
    return StructType(fields)

def create_iceberg_table_if_not_exists(spark, table_name, schema, partition_fields):
    """Create Iceberg table if it doesn't exist"""
    try:
        # Check if table exists
        spark.sql(f"DESCRIBE TABLE iceberg.analytics_logs.{table_name}")
        print(f"Table iceberg.analytics_logs.{table_name} already exists")
    except Exception:
        print(f"Creating table iceberg.analytics_logs.{table_name}")
        
        # Build CREATE TABLE statement
        fields_ddl = ", ".join([f"`{f.name}` {f.dataType.simpleString()}" for f in schema.fields])
        
        partition_clause = ""
        if partition_fields:
            partition_clause = f"PARTITIONED BY ({', '.join(partition_fields)})"
        
        create_stmt = f"""
        CREATE TABLE IF NOT EXISTS iceberg.analytics_logs.{table_name} (
            {fields_ddl}
        )
        USING iceberg
        {partition_clause}
        TBLPROPERTIES (
            'format-version' = '2',
            'write.parquet.compression-codec' = 'snappy'
        )
        """
        
        spark.sql(create_stmt)
        print(f"Created table iceberg.analytics_logs.{table_name}")

def process_topic(spark, config):
    """Process a single Kafka topic and write to Iceberg.

    Honors the optional `transport.encoding` field on the config:
      - encoding="json" (default): parse Kafka value as JSON envelope.
      - encoding="avro": decode Confluent-wire-format Avro using the schema
        fetched from `transport.schema_registry_url`.

    Configs whose transport.sinks does NOT include "kafka" are skipped — they
    belong to the s3-to-iceberg consumer instead.

    To enable Avro: add to the log-config JSON:
        "transport": {"encoding": "avro", "schema_registry_url": "http://schema-registry:8081"}
    """
    transport = config.get("transport") or {}
    sinks = [s.lower() for s in (transport.get("sinks") or ["kafka"])]
    if "kafka" not in sinks:
        return None  # not our problem; the s3-to-iceberg consumer handles it
    topic = config["kafka"]["topic"]
    full_table_name = config.get("warehouse", {}).get("table_name", f"analytics_logs.{config['name'].lower()}")
    table_name = full_table_name.split(".")[-1] if "." in full_table_name else full_table_name
    schema = get_schema_for_config(config)
    partition_fields = config.get("iceberg", {}).get("partition_fields", [])
    transport = config.get("transport") or {}
    encoding = (transport.get("encoding") or "json").lower()

    print(f"\n=== Processing topic: {topic} -> table: {table_name} (encoding={encoding}) ===")
    create_iceberg_table_if_not_exists(spark, table_name, schema, partition_fields)

    df = spark \
        .readStream \
        .format("kafka") \
        .option("kafka.bootstrap.servers", "kafka:29092") \
        .option("subscribe", topic) \
        .option("startingOffsets", "earliest") \
        .option("failOnDataLoss", "false") \
        .load()

    envelope_schema = StructType([
        StructField("_log_type", StringType(), False),
        StructField("_log_class", StringType(), False),
        StructField("_version", StringType(), False),
        StructField("data", schema, False)
    ])

    if encoding == "avro":
        # Confluent wire format = [0x00][4-byte schema id][avro binary].
        # We strip the 5-byte header and use Spark's from_avro on the rest.
        sr_url = transport.get("schema_registry_url") or os.getenv("SCHEMA_REGISTRY_URL")
        if not sr_url:
            raise ValueError(f"topic {topic}: encoding=avro but no schema_registry_url set")
        try:
            from pyspark.sql.avro.functions import from_avro
            from pyspark.sql.functions import expr
        except ImportError as e:
            raise ImportError(
                "Avro decoding requires the spark-avro package on the classpath. "
                "Add --packages org.apache.spark:spark-avro_2.12:<version> to spark-submit."
            ) from e
        # Build the Avro schema JSON from the config fields. Match the
        # producer-side derive_avro_schema() in the Python sinks module.
        from urllib.request import urlopen
        # Fetch latest schema from SR (it was registered by the producer)
        with urlopen(f"{sr_url.rstrip('/')}/subjects/{topic}-value/versions/latest", timeout=10) as r:
            avro_schema_str = json.loads(r.read())["schema"]
        parsed_df = df \
            .selectExpr("substring(value, 6, length(value)-5) as avro_bytes") \
            .select(from_avro(col("avro_bytes"), avro_schema_str).alias("envelope")) \
            .select("envelope.data.*")
    else:
        parsed_df = df.select(
            from_json(col("value").cast("string"), envelope_schema).alias("envelope")
        ).select("envelope.data.*")
    
    # Convert timestamp fields if needed
    for field in schema.fields:
        if isinstance(field.dataType, TimestampType):
            parsed_df = parsed_df.withColumn(
                field.name,
                to_timestamp(col(field.name))
            )
    
    # Write to Iceberg
    query = parsed_df \
        .writeStream \
        .format("iceberg") \
        .outputMode("append") \
        .option("checkpointLocation", f"/opt/spark-data/checkpoints/{table_name}") \
        .option("path", f"iceberg.analytics_logs.{table_name}") \
        .option("fanout-enabled", "true") \
        .start()
    
    print(f"Started streaming query for {topic}")
    return query

def main():
    print("Starting Kafka to Iceberg Consumer...")
    
    # Create Spark session
    spark = SparkSession.builder \
        .appName("KafkaToIcebergConsumer") \
        .getOrCreate()
    
    # Create analytics_logs namespace if not exists
    try:
        spark.sql("CREATE NAMESPACE IF NOT EXISTS iceberg.analytics_logs")
        print("Created/verified namespace iceberg.analytics_logs")
    except Exception as e:
        print(f"Note: {e}")
    
    # Read all config files
    queries = []
    for filename in os.listdir(CONFIG_DIR):
        if filename.endswith(".json"):
            config_path = os.path.join(CONFIG_DIR, filename)
            print(f"Loading config: {config_path}")
            
            with open(config_path, 'r') as f:
                config = json.load(f)
            
            # Skip if no iceberg section (for configs that don't need processing)
            if "iceberg" in config and not config.get("iceberg", {}).get("enabled", True):
                print(f"Skipping {filename} - Iceberg processing disabled")
                continue
            
            try:
                query = process_topic(spark, config)
                if query is None:
                    print(f"Skipping {filename} (not a kafka transport)")
                    continue
                queries.append(query)
            except Exception as e:
                print(f"Error processing {filename}: {e}")
                import traceback
                traceback.print_exc()
    
    if not queries:
        print("No queries started - exiting")
        return
    
    print(f"\n=== Started {len(queries)} streaming queries ===")
    print("Consumer is running. Press Ctrl+C to stop.")
    
    # Wait for all queries
    for query in queries:
        query.awaitTermination()

if __name__ == "__main__":
    main()
