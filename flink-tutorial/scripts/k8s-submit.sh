#!/usr/bin/env bash
# Submit a jar to the k8s Flink session cluster.
set -euo pipefail
cd "$(dirname "$0")/.."

CLASS="${1:-com.example.flink.HelloTableApi}"
JAR="${2:-examples/target/flink-table-tutorial-1.0.0.jar}"

if [ ! -f "$JAR" ]; then
  echo "Jar not found: $JAR — run ./scripts/build.sh first." >&2
  exit 2
fi

JM_POD=$(kubectl -n flink-tutorial get pod -l component=jobmanager -o jsonpath='{.items[0].metadata.name}')
echo "JM pod: $JM_POD"

kubectl -n flink-tutorial cp "$JAR" "$JM_POD:/tmp/job.jar"
kubectl -n flink-tutorial exec "$JM_POD" -- flink run -c "$CLASS" -d /tmp/job.jar
echo
echo "Submitted. Watch progress: open http://localhost:30030/#/job/running"
