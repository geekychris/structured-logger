#!/usr/bin/env bash
# Submit an uber-jar to the running Flink session cluster.
#
# Usage:
#   ./submit.sh com.example.flink.HelloTableApi
#   ./submit.sh com.example.flink.KafkaToConsoleTableApi
#
# Optional second arg: jar path (defaults to the built tutorial uber-jar).
set -euo pipefail
cd "$(dirname "$0")/.."

CLASS="${1:-com.example.flink.HelloTableApi}"
JAR="${2:-examples/target/flink-table-tutorial-1.0.0.jar}"

if [ ! -f "$JAR" ]; then
  echo "Jar not found: $JAR — run ./scripts/build.sh first." >&2
  exit 2
fi

# Copy jar into the JM container, then submit via the in-container CLI so the
# job sees the right classpath.
docker cp "$JAR" flink-jobmanager:/tmp/job.jar
docker exec flink-jobmanager flink run -c "$CLASS" -d /tmp/job.jar
echo
echo "Submitted. Watch progress at: http://127.0.0.1:18030/#/job/running"
