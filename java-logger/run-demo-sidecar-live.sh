#!/usr/bin/env bash
# Demo 2b: live, long-running sidecar pipeline. Produces ~1 record/sec and
# runs the sidecar concurrently. Open a second terminal to watch records flow:
#
#   tail -f /tmp/sl-demo/shipped/delivered.ndjson      # the sidecar's output
#   tail -f /tmp/sl-demo/app-logs/orders.ndjson        # the app's source
#
# Override duration with DEMO_SECONDS=N (default: 120).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [[ ! -d demos/target/classes ]] || [[ ! -d demos/target/lib ]]; then
    echo "Build artifacts missing. Run ./build.sh first."
    exit 1
fi

: "${DEMO_SECONDS:=120}"
export DEMO_SECONDS

CP="demos/target/classes:demos/target/lib/*"
exec java -cp "$CP" com.logging.demo.LiveSidecarDemo
