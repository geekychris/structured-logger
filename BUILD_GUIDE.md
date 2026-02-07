# Build and Setup Guide

This guide explains how to build and set up the Structured Logging System.

## Quick Setup (Recommended)

For first-time setup or comprehensive rebuilds:

```bash
./build-and-setup.sh
```

This interactive script will:
1. Check all system dependencies (Docker, Java, Maven, Python)
2. Verify Docker services are running
3. Set up Python virtual environment
4. Build all Java components
5. Build Spark consumer
6. Optionally generate loggers from configs
7. Offer to run test examples

### What Happens If Docker Services Aren't Running?

The script will:
- List which services are missing (Kafka, Spark, MinIO, etc.)
- Show you how to start them
- Give you the option to continue or exit

To start the required services:
```bash
# Option 1: Use the included script
./start-demo-with-trino-docker.sh

# Option 2: Use docker-compose from your lakehouse directory
cd ../spark_minio_trino
docker-compose up -d
```

## Prerequisites

### System Requirements

- **Docker**: Latest version, running
- **Java**: JDK 11 or later (OpenJDK or Amazon Corretto)
- **Maven**: 3.6 or later
- **Python**: 3.8 or later with venv support

### Required Docker Services

The following services must be running:
- Kafka (message streaming)
- Spark (stream processing)
- MinIO (object storage)
- Hive Metastore (catalog)
- Trino (query engine)

## Manual Build Steps

If you prefer to build components individually:

### 1. Python Virtual Environment

```bash
# One-time setup
bash setup_python_env.sh

# Or manually
python3 -m venv python-logger/venv
source python-logger/venv/bin/activate
pip install -r python-logger/requirements.txt
```

### 2. Java Logger Library

```bash
cd java-logger
mvn clean package
cd ..
```

Output: `java-logger/target/structured-logging-java-1.0.0.jar`

### 3. Spark Consumer

```bash
cd spark-consumer
mvn clean package
cd ..
```

Output: `spark-consumer/target/structured-log-consumer-1.0.0.jar`

### 4. Generate Loggers (Optional)

If you have log configurations in `log-configs/`:

```bash
# Generate from all configs
make generate

# Or generate from specific config
python3 generators/generate_loggers.py log-configs/user_events.json
```

## Using Make

The project includes a Makefile with common tasks:

```bash
# Show all available targets
make help

# Generate loggers from log-configs/
make generate

# Build Java logger
make build-java

# Setup Python environment
make build-python

# Build Spark consumer
make build-spark

# Build everything
make build-all

# Clean all build artifacts
make clean
```

## Running Examples

After building, you can run the examples:

### Java Example

```bash
./run-java-demo.sh
```

This will:
- Check if Kafka is running
- Build the Java logger if needed
- Compile and run JavaExample.java
- Send test events to Kafka

### Python Example

```bash
./examples/python_example.py
```

The Python scripts use the virtual environment automatically.

### Test Data Generator

```bash
# Generate 100 events
./examples/generate_test_data.py --count 100

# Generate continuously at 10 events/second
./examples/generate_test_data.py --continuous --rate 10

# Generate for 5 minutes
./examples/generate_test_data.py --duration 300 --rate 5
```

## Troubleshooting

### Docker Services Not Running

**Problem**: Script reports missing Docker services

**Solution**: 
```bash
# Check which containers are running
docker ps

# Start the lakehouse infrastructure
./start-demo-with-trino-docker.sh
```

### Java Build Fails

**Problem**: Maven build fails with dependency errors

**Solution**:
```bash
# Check Java version (need 11+)
java -version

# Check JAVA_HOME is set correctly
echo $JAVA_HOME

# Clean and rebuild
cd java-logger
mvn clean install -U
```

### Python Import Errors

**Problem**: `ModuleNotFoundError: No module named 'kafka'`

**Solution**:
```bash
# Make sure you're using the virtual environment
source python-logger/venv/bin/activate
pip install -r python-logger/requirements.txt

# Or re-run setup script
bash setup_python_env.sh
```

### Spark Consumer Won't Start

**Problem**: Consumer fails to start or crashes

**Solution**:
```bash
# Check Spark is running
docker ps | grep spark

# View consumer logs
docker exec spark-master tail -f /opt/spark-data/consumer.log

# Restart consumer
./start-demo-with-trino-docker.sh
```

## Verification

After building, verify everything works:

```bash
# Run the full demo
./run-java-demo.sh

# Query data in Trino
docker exec -it trino trino
USE local.analytics_logs;
SHOW TABLES;
SELECT * FROM user_events LIMIT 10;
```

## Clean Rebuild

To start fresh:

```bash
# Clean all build artifacts
make clean

# Remove generated code
rm -rf java-logger/src/main/java/com/logging/generated
rm -rf python-logger/structured_logging/generated

# Rebuild everything
./build-and-setup.sh
```

## CI/CD Integration

For automated builds in CI/CD pipelines:

```bash
# Non-interactive build (no prompts)
./build-and-setup.sh < /dev/null

# Or use make targets
make clean
make generate
make build-all

# Run tests
./examples/python_example.py
```

## Next Steps

After building:

1. **Create your log configs** in `log-configs/`
2. **Generate loggers** with `make generate`
3. **Use loggers** in your applications
4. **Start consumer** to process logs
5. **Query data** in Trino

See also:
- [README.md](README.md) - Project overview
- [QUICKSTART.md](QUICKSTART.md) - Quick start guide
- [ADDING_NEW_LOGGERS.md](ADDING_NEW_LOGGERS.md) - Adding new log types
- [examples/README.md](examples/README.md) - Example usage
