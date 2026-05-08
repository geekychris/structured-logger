#!/usr/bin/env bash
# Run every unit and integration test across all modules. Prints a per-module
# summary at the end. None of these tests need Kafka, NATS, or any external
# service — they spin up Flink mini-clusters and use temp dirs.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

LOG=$(mktemp -t structured-logging-test.XXXXXX)
trap 'rm -f "$LOG"' EXIT

echo "==> mvn verify (output -> $LOG)"
if ! mvn verify > "$LOG" 2>&1; then
    echo
    echo "BUILD FAILED. Last 60 lines of output:"
    tail -60 "$LOG"
    exit 1
fi

echo
echo "Per-class results:"
grep -E "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+, Time elapsed:.*-- in " "$LOG" \
    | sed 's/^\[INFO\] //' \
    | awk '{
        for (i=1; i<=NF; i++) if ($i == "in") { cls=$(i+1); break }
        printf "  %-60s %s %s %s %s\n", cls, $2, $3, $4, $5, $6
      }' \
    | sort

echo
echo "Per-module totals (across modules in build order):"
# The per-module total is the "Tests run: N, Failures: ..." line WITHOUT "-- in".
grep -E "^\[INFO\] Tests run: [0-9]+, Failures:" "$LOG" \
    | grep -v "\-\- in " \
    | sed 's/^\[INFO\] /  /'

echo
echo "BUILD SUCCESS"
