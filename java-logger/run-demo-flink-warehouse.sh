#!/usr/bin/env bash
# Demo 4a: warehouse + streaming. App writes user_events NDJSON via FileSink;
# the same file feeds (a) the warehouse landing path and (b) a Flink Table API
# SQL aggregation that runs in a local mini-cluster. No Kafka required.
#
# Flink emits a lot of pekko/cluster/lifecycle log noise on stderr that
# competes with our demo prints; we silence it but capture for diagnostics.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

for d in stream-processor/target/classes stream-processor/target/lib core/target/classes core/target/lib; do
    if [[ ! -d "$d" ]]; then
        echo "Build artifact missing ($d). Run ./build.sh first."
        exit 1
    fi
done

CP="stream-processor/target/classes:stream-processor/target/lib/*:core/target/classes:core/target/lib/*"
ERR_LOG=$(mktemp -t structured-logging-flink.XXXXXX)
trap 'rm -f "$ERR_LOG"' EXIT

if ! java -cp "$CP" com.logging.stream.demos.WarehouseAndStreamingDemo 2>"$ERR_LOG"; then
    echo
    echo "Demo failed. Last 40 lines of Flink/JVM stderr:"
    tail -40 "$ERR_LOG"
    exit 1
fi
