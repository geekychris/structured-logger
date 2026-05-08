#!/usr/bin/env bash
# Build all four modules (core, sidecar, stream-processor, demos) and copy
# runtime dependencies into each module's target/lib so the run-demo-*.sh
# scripts can launch with a simple classpath glob.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

SKIP_TESTS="${SKIP_TESTS:-true}"
MVN_FLAGS=("-q")
if [[ "$SKIP_TESTS" == "true" ]]; then
    MVN_FLAGS+=("-DskipTests")
fi

echo "==> mvn package ${MVN_FLAGS[*]}"
mvn package "${MVN_FLAGS[@]}"

echo
echo "Build artifacts:"
for m in core sidecar stream-processor demos; do
    jar=$(find "$m/target" -maxdepth 1 -name "structured-logging-*.jar" -not -name "*-sources.jar" 2>/dev/null | head -1)
    if [[ -n "$jar" ]]; then
        printf "  %-22s %s\n" "$m" "$jar"
    else
        printf "  %-22s (no jar built)\n" "$m"
    fi
done
echo
echo "Done. Run ./test.sh to execute the test suite, or ./run-demo-*.sh to try a demo."
