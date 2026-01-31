package com.logging.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.streaming.StreamingQuery;
import org.apache.spark.sql.streaming.StreamingQueryException;
import org.apache.spark.sql.streaming.Trigger;
import org.apache.spark.sql.types.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.spark.sql.functions.*;

/**
 * Metadata-driven Spark consumer for structured logs.
 * Reads from Kafka topics and writes to Iceberg tables based on log configs.
 */
public class StructuredLogConsumer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: StructuredLogConsumer <config-file-or-dir> <kafka-bootstrap-servers> <warehouse-path> [checkpoint-path]");
            System.exit(1);
        }

        String configPath = args[0];
        String kafkaBootstrapServers = args[1];
        String warehousePath = args[2];
        String checkpointPath = args.length > 3 ? args[3] : warehousePath + "/checkpoints";

        // Load configurations
        List<LogConfig> configs = loadConfigs(configPath);
        System.out.println("Loaded " + configs.size() + " log configurations");

        // Initialize Spark with Hive metastore catalog
        // This ensures tables are registered and accessible from Trino
        SparkSession spark = SparkSession.builder()
                .appName("StructuredLogConsumer")
                .config("spark.sql.extensions", "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
                .config("spark.sql.catalog.spark_catalog", "org.apache.iceberg.spark.SparkSessionCatalog")
                .config("spark.sql.catalog.spark_catalog.type", "hive")
                .config("spark.sql.catalog.local", "org.apache.iceberg.spark.SparkCatalog")
                .config("spark.sql.catalog.local.type", "hive")
                .config("spark.sql.catalog.local.warehouse", warehousePath)
                .config("spark.sql.streaming.schemaInference", "true")
                .getOrCreate();

        spark.sparkContext().setLogLevel("WARN");

        // Process each log config
        List<StreamingQuery> queries = new ArrayList<>();
        for (LogConfig config : configs) {
            StreamingQuery query = processLogConfig(spark, config, kafkaBootstrapServers, checkpointPath, warehousePath);
            queries.add(query);
        }

        // Wait for all queries
        System.out.println("Started " + queries.size() + " streaming queries");
        for (StreamingQuery query : queries) {
            query.awaitTermination();
        }
    }

    private static List<LogConfig> loadConfigs(String configPath) throws IOException {
        Path path = Paths.get(configPath);
        
        if (Files.isDirectory(path)) {
            // Load all JSON files from directory
            try (Stream<Path> paths = Files.list(path)) {
                return paths
                        .filter(p -> p.toString().endsWith(".json"))
                        .map(p -> {
                            try {
                                return loadConfig(p.toString());
                            } catch (IOException e) {
                                throw new RuntimeException("Failed to load config: " + p, e);
                            }
                        })
                        .collect(Collectors.toList());
            }
        } else {
            // Load single file
            return Collections.singletonList(loadConfig(configPath));
        }
    }

    private static LogConfig loadConfig(String filePath) throws IOException {
        return OBJECT_MAPPER.readValue(new File(filePath), LogConfig.class);
    }

    private static StreamingQuery processLogConfig(
            SparkSession spark,
            LogConfig config,
            String kafkaBootstrapServers,
            String checkpointPath,
            String warehousePath) throws TimeoutException {

        System.out.println("Processing log config: " + config.name + " -> " + config.warehouse.tableName);

        // Create schema from config (business data only)
        StructType dataSchema = createSchema(config.fields);
        
        // Create envelope schema with metadata fields
        StructType envelopeSchema = DataTypes.createStructType(Arrays.asList(
            DataTypes.createStructField("_log_type", DataTypes.StringType, false),
            DataTypes.createStructField("_log_class", DataTypes.StringType, false),
            DataTypes.createStructField("_version", DataTypes.StringType, false),
            DataTypes.createStructField("data", dataSchema, false)
        ));

        // Read from Kafka
        Dataset<Row> kafkaDF = spark.readStream()
                .format("kafka")
                .option("kafka.bootstrap.servers", kafkaBootstrapServers)
                .option("subscribe", config.kafka.topic)
                .option("startingOffsets", "latest")
                .option("failOnDataLoss", "false")
                .load();

        // Parse JSON with envelope, then extract data field
        Dataset<Row> parsedDF = kafkaDF
                .selectExpr("CAST(value AS STRING) as json_value")
                .select(from_json(col("json_value"), envelopeSchema).as("envelope"))
                .select("envelope.data.*");

        // Add processing metadata
        Dataset<Row> enrichedDF = parsedDF
                .withColumn("_ingestion_timestamp", current_timestamp())
                .withColumn("_ingestion_date", current_date());

        // Create database and table before streaming
        ensureTableExists(spark, config, warehousePath);

        // Write to Iceberg - use catalog.table format
        String icebergTable = "local." + config.warehouse.tableName;
        System.out.println("Writing to Iceberg table: " + icebergTable);
        
        StreamingQuery query = enrichedDF.writeStream()
                .format("iceberg")
                .outputMode("append")
                .trigger(Trigger.ProcessingTime("10 seconds"))
                .option("path", icebergTable)
                .option("checkpointLocation", checkpointPath + "/" + config.name)
                .start();

        System.out.println("Started streaming query for " + config.name);
        return query;
    }

    private static StructType createSchema(List<FieldConfig> fields) {
        List<StructField> structFields = new ArrayList<>();
        for (FieldConfig field : fields) {
            DataType dataType = mapFieldType(field.type);
            boolean nullable = !field.required;
            structFields.add(DataTypes.createStructField(field.name, dataType, nullable));
        }
        return DataTypes.createStructType(structFields);
    }

    private static DataType mapFieldType(String fieldType) {
        switch (fieldType) {
            case "string":
                return DataTypes.StringType;
            case "int":
                return DataTypes.IntegerType;
            case "long":
                return DataTypes.LongType;
            case "float":
                return DataTypes.FloatType;
            case "double":
                return DataTypes.DoubleType;
            case "boolean":
                return DataTypes.BooleanType;
            case "timestamp":
                return DataTypes.TimestampType;
            case "date":
                return DataTypes.DateType;
            case "array<string>":
                return DataTypes.createArrayType(DataTypes.StringType);
            case "array<int>":
                return DataTypes.createArrayType(DataTypes.IntegerType);
            case "array<long>":
                return DataTypes.createArrayType(DataTypes.LongType);
            case "map<string,string>":
                return DataTypes.createMapType(DataTypes.StringType, DataTypes.StringType);
            default:
                throw new IllegalArgumentException("Unsupported field type: " + fieldType);
        }
    }

    private static void ensureTableExists(SparkSession spark, LogConfig config, String warehousePath) {
        String tableName = config.warehouse.tableName;  // e.g., "analytics_logs.api_metrics"
        StructType schema = createSchema(config.fields);

        // Add metadata fields
        List<StructField> allFields = new ArrayList<>(Arrays.asList(schema.fields()));
        allFields.add(DataTypes.createStructField("_ingestion_timestamp", DataTypes.TimestampType, false));
        allFields.add(DataTypes.createStructField("_ingestion_date", DataTypes.DateType, false));
        StructType fullSchema = DataTypes.createStructType(allFields);

        try {
            // Parse database.table (Hive only supports 2-part names)
            String[] parts = tableName.split("\\.");
            
            // Create database in Hive metastore
            if (parts.length >= 2) {
                String dbName = parts[0];
                System.out.println("Creating database if not exists: local." + dbName);
                spark.sql("CREATE DATABASE IF NOT EXISTS local." + dbName + " LOCATION '" + warehousePath + "/" + dbName + "'");
            }

            String fullTableName = "local." + tableName;
            
            // Check if table exists
            boolean tableExists = tableExists(spark, fullTableName);
            
            if (!tableExists) {
                // Create new table
                createNewTable(spark, fullTableName, fullSchema, config);
            } else {
                // Table exists - check for schema evolution
                evolveSchema(spark, fullTableName, fullSchema, config);
            }
            
            System.out.println("✓ Table ready: " + fullTableName);
        } catch (Exception e) {
            System.err.println("Error managing table " + tableName + ": " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to manage table", e);
        }
    }
    
    private static boolean tableExists(SparkSession spark, String fullTableName) {
        try {
            spark.sql("DESCRIBE TABLE " + fullTableName).collect();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    private static void createNewTable(SparkSession spark, String fullTableName, StructType schema, LogConfig config) {
        System.out.println("Creating new Iceberg table: " + fullTableName);
        
        StringBuilder fieldsSQL = new StringBuilder();
        for (int i = 0; i < schema.fields().length; i++) {
            StructField field = schema.fields()[i];
            if (i > 0) fieldsSQL.append(", ");
            fieldsSQL.append(field.name()).append(" ").append(sparkTypeToSQLType(field.dataType()));
        }

        String partitionByClause = "";
        if (config.warehouse.partitionBy != null && !config.warehouse.partitionBy.isEmpty()) {
            partitionByClause = "PARTITIONED BY (" + String.join(", ", config.warehouse.partitionBy) + ")";
        }

        String tblProperties = "TBLPROPERTIES ('write.format.default'='parquet', " +
                "'write.parquet.compression-codec'='snappy', " +
                "'write.metadata.compression-codec'='gzip'";
        
        if (config.warehouse.sortBy != null && !config.warehouse.sortBy.isEmpty()) {
            tblProperties += ", 'sort.order'='" + String.join(", ", config.warehouse.sortBy) + "'";
        }
        tblProperties += ")";

        String createTableSQL = String.format(
                "CREATE TABLE %s (%s) USING iceberg %s %s",
                fullTableName, fieldsSQL.toString(), partitionByClause, tblProperties
        );

        System.out.println("Executing: " + createTableSQL);
        spark.sql(createTableSQL);
        System.out.println("✓ Created table: " + fullTableName);
    }
    
    private static void evolveSchema(SparkSession spark, String fullTableName, StructType desiredSchema, LogConfig config) {
        System.out.println("Checking schema evolution for: " + fullTableName);
        
        // Get current schema
        Dataset<Row> currentTable = spark.table(fullTableName);
        StructType currentSchema = currentTable.schema();
        
        // Build map of current fields
        Map<String, StructField> currentFields = new HashMap<>();
        for (StructField field : currentSchema.fields()) {
            currentFields.put(field.name(), field);
        }
        
        // Check for new fields to add
        List<String> alterStatements = new ArrayList<>();
        for (StructField desiredField : desiredSchema.fields()) {
            String fieldName = desiredField.name();
            
            if (!currentFields.containsKey(fieldName)) {
                // New field - add it
                String sqlType = sparkTypeToSQLType(desiredField.dataType());
                String alterSQL = String.format(
                    "ALTER TABLE %s ADD COLUMN %s %s",
                    fullTableName, fieldName, sqlType
                );
                alterStatements.add(alterSQL);
                System.out.println("  + Adding new column: " + fieldName + " " + sqlType);
            } else {
                // Field exists - check type compatibility
                StructField currentField = currentFields.get(fieldName);
                if (!areTypesCompatible(currentField.dataType(), desiredField.dataType())) {
                    System.err.println("  ! WARNING: Field '" + fieldName + "' type mismatch:");
                    System.err.println("    Current: " + currentField.dataType().typeName());
                    System.err.println("    Desired: " + desiredField.dataType().typeName());
                    System.err.println("    Type changes are not supported - keeping current type");
                }
            }
        }
        
        // Check for removed fields (warning only - Iceberg doesn't delete columns)
        for (String currentFieldName : currentFields.keySet()) {
            boolean stillExists = false;
            for (StructField desiredField : desiredSchema.fields()) {
                if (desiredField.name().equals(currentFieldName)) {
                    stillExists = true;
                    break;
                }
            }
            if (!stillExists && !currentFieldName.startsWith("_ingestion")) {
                System.out.println("  ! INFO: Field '" + currentFieldName + "' removed from config but will remain in table");
                System.out.println("    (Iceberg preserves schema history - old data remains queryable)");
            }
        }
        
        // Execute ALTER statements
        if (!alterStatements.isEmpty()) {
            System.out.println("Evolving schema with " + alterStatements.size() + " change(s)...");
            for (String alterSQL : alterStatements) {
                System.out.println("Executing: " + alterSQL);
                spark.sql(alterSQL);
            }
            System.out.println("✓ Schema evolved successfully");
        } else {
            System.out.println("✓ Schema unchanged");
        }
    }
    
    private static boolean areTypesCompatible(DataType current, DataType desired) {
        // Exact match
        if (current.equals(desired)) {
            return true;
        }
        
        // Widening conversions that Iceberg supports
        if (current instanceof IntegerType && desired instanceof LongType) {
            return true;  // int -> long is safe
        }
        if (current instanceof FloatType && desired instanceof DoubleType) {
            return true;  // float -> double is safe
        }
        
        // Everything else is incompatible
        return false;
    }

    private static String sparkTypeToSQLType(DataType dataType) {
        if (dataType instanceof StringType) {
            return "STRING";
        } else if (dataType instanceof IntegerType) {
            return "INT";
        } else if (dataType instanceof LongType) {
            return "LONG";
        } else if (dataType instanceof FloatType) {
            return "FLOAT";
        } else if (dataType instanceof DoubleType) {
            return "DOUBLE";
        } else if (dataType instanceof BooleanType) {
            return "BOOLEAN";
        } else if (dataType instanceof TimestampType) {
            return "TIMESTAMP";
        } else if (dataType instanceof DateType) {
            return "DATE";
        } else if (dataType instanceof ArrayType) {
            ArrayType arrayType = (ArrayType) dataType;
            return "ARRAY<" + sparkTypeToSQLType(arrayType.elementType()) + ">";
        } else if (dataType instanceof MapType) {
            MapType mapType = (MapType) dataType;
            return "MAP<" + sparkTypeToSQLType(mapType.keyType()) + ", " + sparkTypeToSQLType(mapType.valueType()) + ">";
        } else {
            throw new IllegalArgumentException("Unsupported Spark type: " + dataType);
        }
    }

    // Configuration classes
    public static class LogConfig {
        public String name;
        public String version;
        public String description;
        public KafkaConfig kafka;
        public WarehouseConfig warehouse;
        public List<FieldConfig> fields;
    }

    public static class KafkaConfig {
        public String topic;
        public int partitions;
        public int replication_factor;
        public long retention_ms;
    }

    public static class WarehouseConfig {
        public String tableName;
        public List<String> partitionBy;
        public List<String> sortBy;
        public Integer retentionDays;
    }

    public static class FieldConfig {
        public String name;
        public String type;
        public boolean required = true;
        public Object defaultValue;
        public String description;
    }
}
