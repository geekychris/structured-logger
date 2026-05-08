#!/usr/bin/env bash
# Demo 2a: file -> sidecar -> file pipeline (one-shot). The app writes NDJSON
# locally; the sidecar tails the dir and ships records to a "delivered" file.
# Same shape as production, only the sidecar's target sink differs (Kafka/NATS).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [[ ! -d demos/target/classes ]] || [[ ! -d demos/target/lib ]]; then
    echo "Build artifacts missing. Run ./build.sh first."
    exit 1
fi

CP="demos/target/classes:demos/target/lib/*"
exec java -cp "$CP" com.logging.demo.FileSinkSidecarDemo
