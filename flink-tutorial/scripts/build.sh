#!/usr/bin/env bash
# Build the examples uber-jar.
set -euo pipefail
cd "$(dirname "$0")/.."
( cd examples && mvn -q clean package )
JAR="examples/target/flink-table-tutorial-1.0.0.jar"
echo "Built: $JAR"
ls -lh "$JAR"
