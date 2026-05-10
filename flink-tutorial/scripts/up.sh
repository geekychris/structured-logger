#!/usr/bin/env bash
# Bring up the Flink session cluster (Docker Compose).
# Joins the same `lakehouse-network` as spark_minio_trino so jobs can reach
# Kafka, MinIO, and Hive Metastore by container name.
#
# Usage:
#   ./up.sh                          # normal start
#   FLINK_DEBUG_SUSPEND=y ./up.sh    # JVM blocks until your debugger attaches
set -euo pipefail
cd "$(dirname "$0")/.."

if ! docker network inspect spark_minio_trino_lakehouse-network >/dev/null 2>&1; then
  cat >&2 <<EOF
ERROR: docker network 'spark_minio_trino_lakehouse-network' does not exist.

Bring up the lakehouse stack first:
  cd ..
  docker compose -f ../spark_minio_trino/docker-compose.yml \\
                 -f lakehouse-override.yml up -d
EOF
  exit 2
fi

docker compose -f compose/docker-compose.yml up -d
echo
echo "Flink Web UI:    http://127.0.0.1:18030"
echo "JobManager JDWP: localhost:18040"
echo "TaskManager JDWP: localhost:18041"
