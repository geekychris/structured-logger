#!/usr/bin/env bash
# Run every demo that doesn't need an external broker, one after another.
# Skips the live sidecar demo (long-running) and the NATS demo (needs broker).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

separator() {
    printf '\n========== %s ==========\n\n' "$1"
}

separator "1. dual logging  (SLF4J + file)"
./run-demo-dual.sh

separator "2. file -> sidecar -> file"
./run-demo-sidecar.sh

separator "3. Flink Table API: warehouse + streaming"
./run-demo-flink-warehouse.sh

separator "4. Flink Table API: streaming-only (tumbling windows)"
./run-demo-flink-stream-only.sh

separator "all demos complete"
