#!/bin/bash
set -e

echo "Starting Hive Metastore initialization..."

# Wait for PostgreSQL to be ready
echo "Waiting for PostgreSQL to be available..."
until pg_isready -h postgres -p 5432 -U hive; do
  echo "PostgreSQL is unavailable - sleeping"
  sleep 2
done
echo "PostgreSQL is up!"

# Check if schema is already initialized
SCHEMA_VERSION=$(/opt/hive/bin/schematool -dbType postgres \
    -userName hive \
    -passWord hivepassword \
    -url jdbc:postgresql://postgres:5432/metastore \
    -driver org.postgresql.Driver \
    -info 2>&1 || true)

if echo "$SCHEMA_VERSION" | grep -q "Metastore schema version:"; then
    echo "Hive metastore schema already initialized"
else
    echo "Initializing Hive metastore schema..."
    /opt/hive/bin/schematool -dbType postgres \
        -userName hive \
        -passWord hivepassword \
        -url jdbc:postgresql://postgres:5432/metastore \
        -driver org.postgresql.Driver \
        -initSchema
    echo "Schema initialization complete!"
fi

# Start the metastore service
echo "Starting Hive Metastore service..."
exec /entrypoint.sh /opt/hive/bin/hive --service metastore
