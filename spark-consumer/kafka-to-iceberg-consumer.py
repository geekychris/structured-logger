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
    """Process a single Kafka topic and write to Iceberg"""
    topic = config["kafka"]["topic"]
    # Use warehouse.table_name if specified, otherwise fall back to lowercased name
    full_table_name = config.get("warehouse", {}).get("table_name", f"analytics_logs.{config['name'].lower()}")
    # Extract just the table name (remove schema prefix if present)
    table_name = full_table_name.split(".")[-1] if "." in full_table_name else full_table_name
    schema = get_schema_for_config(config)
    partition_fields = config.get("iceberg", {}).get("partition_fields", [])
    
    print(f"\n=== Processing topic: {topic} -> table: {table_name} ===")
    
    # Create table if needed
    create_iceberg_table_if_not_exists(spark, table_name, schema, partition_fields)
    
    # Read from Kafka
    df = spark \
        .readStream \
        .format("kafka") \
        .option("kafka.bootstrap.servers", "kafka:29092") \
        .option("subscribe", topic) \
        .option("startingOffsets", "earliest") \
        .option("failOnDataLoss", "false") \
        .load()
    
    # Create envelope schema with metadata fields
    envelope_schema = StructType([
        StructField("_log_type", StringType(), False),
        StructField("_log_class", StringType(), False),
        StructField("_version", StringType(), False),
        StructField("data", schema, False)
    ])
    
    # Parse JSON with envelope, then extract data field
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
