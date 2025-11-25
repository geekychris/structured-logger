# Quick Move Instructions

The `structured-logging` project is now fully standalone and ready to move!

## TL;DR

```bash
# Stop parent project
cd /Users/chris/code/warp_experiments/trino_docker
docker-compose down

# Move structured-logging to same level
cd /Users/chris/code/warp_experiments
mv trino_docker/structured-logging ./

# Start standalone project
cd structured-logging
docker-compose up -d
./start-consumer.sh
```

## What You Get

A complete, self-contained structured logging system:

```
structured-logging/
├── docker-compose.yml       # All services (MinIO, Kafka, Spark, Trino, etc.)
├── docker/                  # Docker configs (Hive, Trino)
├── spark-consumer/          # Java Spark consumer
├── java-logger/            # Java logger library
├── python-logger/          # Python logger library  
├── generators/             # Code generators
├── log-configs/            # Log configurations
└── examples/               # Working examples
```

## Same Ports, Same Names

Since you won't run both projects at once:
- **Ports**: Identical (9000, 9092, 8081, etc.)
- **Container names**: Identical (`minio`, `kafka`, `spark-master`, etc.)
- **Commands**: Work the same way

Example commands work in both projects:
```bash
docker exec -it trino trino
docker exec spark-master ps aux
docker exec minio mc ls myminio/warehouse/
```

## No Changes Needed

Once moved, you can use it exactly like before:

```bash
# Start services
docker-compose up -d

# Start consumer  
./start-consumer.sh

# Run examples
cd examples && python3 python_example.py

# Query data
docker exec -it trino trino \
  --execute "SELECT * FROM iceberg.analytics_logs.user_events LIMIT 10"
```

## Documentation

- **[STANDALONE_SETUP.md](STANDALONE_SETUP.md)** - Complete setup guide
- **[MIGRATION_NOTES.md](MIGRATION_NOTES.md)** - Detailed migration info
- **[BUILD_AND_RUN.md](BUILD_AND_RUN.md)** - Build and usage guide
- **[QUICK_REFERENCE.md](QUICK_REFERENCE.md)** - Common commands

## That's It!

The project has zero dependencies on the parent `trino_docker` directory. Move it anywhere!
