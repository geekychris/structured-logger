#!/usr/bin/env bash
# Demo 1: dual logging — same record fans out to SLF4J AND a structured
# NDJSON file at the same time. No external services required.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [[ ! -d demos/target/classes ]] || [[ ! -d demos/target/lib ]]; then
    echo "Build artifacts missing. Run ./build.sh first."
    exit 1
fi

CP="demos/target/classes:demos/target/lib/*"
exec java -cp "$CP" com.logging.demo.DualLoggingDemo
