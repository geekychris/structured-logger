#!/usr/bin/env bash
# Demo 4b: streaming-only. High-volume ephemeral session pings; Flink Table
# API runs a 5-second tumbling window aggregation. No Kafka required.
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

if ! java -cp "$CP" com.logging.stream.demos.StreamOnlyDemo 2>"$ERR_LOG"; then
    echo
    echo "Demo failed. Last 40 lines of Flink/JVM stderr:"
    tail -40 "$ERR_LOG"
    exit 1
fi
