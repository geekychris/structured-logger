#!/usr/bin/env bash
# Demo 3: publish to NATS JetStream. Requires a local NATS broker:
#   docker run --rm -p 4222:4222 nats:2.10 -js
#
# Verify with:
#   nats sub 'logs.>'   # in another terminal
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [[ ! -d demos/target/classes ]] || [[ ! -d demos/target/lib ]]; then
    echo "Build artifacts missing. Run ./build.sh first."
    exit 1
fi

: "${NATS_URL:=nats://127.0.0.1:4222}"
export NATS_URL

CP="demos/target/classes:demos/target/lib/*"
exec java -cp "$CP" com.logging.demo.NatsJetStreamDemo
