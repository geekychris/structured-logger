#!/usr/bin/env bash
# Run a single approach end to end and capture metrics.
# Usage: ./run-bench.sh <A|B|C|D> [duration_s] [rps]
set -euo pipefail

APPROACH="${1:?usage: run-bench.sh <A|B|C|D> [duration_s] [rps]}"
DURATION_S="${2:-180}"
RPS="${3:-2000}"
WARMUP_S="${WARMUP_S:-20}"

cd "$(dirname "$0")/.."

RUN_ID="$APPROACH"
RESULTS_DIR="results/$RUN_ID"
mkdir -p "$RESULTS_DIR"

export RPS DURATION_S WARMUP_S RUN_ID

case "$APPROACH" in
  A) SERVICES="kafka driver-A consumer-A" ;;
  B) SERVICES="kafka schema-registry driver-B consumer-B" ;;
  C) SERVICES="minio minio-init driver-C sidecar-C consumer-C" ;;
  D) SERVICES="minio minio-init driver-D sidecar-D consumer-D" ;;
  E) SERVICES="minio minio-init warpstream driver-E consumer-E" ;;
  *) echo "unknown approach $APPROACH"; exit 2 ;;
esac

echo "=== bench/$APPROACH: rps=$RPS duration=${DURATION_S}s warmup=${WARMUP_S}s ==="

# Bring up support services first (kafka/minio/schema-registry/warpstream) and wait for health
case "$APPROACH" in
  A) docker compose --profile $APPROACH up -d kafka ;;
  B) docker compose --profile $APPROACH up -d kafka schema-registry ;;
  C|D) docker compose --profile $APPROACH up -d minio minio-init ;;
  E) docker compose --profile $APPROACH up -d minio minio-init ;;
esac

echo "--- waiting for support services to become healthy ---"
sleep 5
case "$APPROACH" in
  A|B)
    until docker compose ps kafka --format json | grep -q '"Health":"healthy"'; do sleep 1; done
    ;;
  B)
    until docker compose ps schema-registry --format json | grep -q '"Health":"healthy"'; do sleep 1; done
    ;;
esac
case "$APPROACH" in
  C|D|E)
    until docker compose ps minio --format json | grep -q '"Health":"healthy"'; do sleep 1; done
    docker compose --profile $APPROACH up minio-init
    ;;
esac

# WarpStream: start agent and give it time to register with the control plane
if [ "$APPROACH" = "E" ]; then
  docker compose --profile $APPROACH up -d warpstream
  echo "--- waiting for WarpStream agent to be ready (~25s) ---"
  for i in $(seq 1 30); do
    if docker exec bench-warpstream wget -q -O- http://localhost:8080/v1/status 2>/dev/null | grep -q '"healthy"'; then
      echo "WarpStream agent healthy after ${i}s"
      break
    fi
    sleep 1
  done
  sleep 5  # extra grace for cluster setup
fi

# Start consumer first so it doesn't miss early records
case "$APPROACH" in
  A) docker compose --profile $APPROACH up -d consumer-A ;;
  B) docker compose --profile $APPROACH up -d consumer-B ;;
  C) docker compose --profile $APPROACH up -d consumer-C ;;
  D) docker compose --profile $APPROACH up -d consumer-D ;;
  E) docker compose --profile $APPROACH up -d consumer-E ;;
esac
sleep 2

# For sidecar approaches, start sidecar before driver so it's tailing
case "$APPROACH" in
  C) docker compose --profile $APPROACH up -d sidecar-C ;;
  D) docker compose --profile $APPROACH up -d sidecar-D ;;
esac
sleep 1

# Begin background stats collection on the relevant containers
STATS_FILE="$RESULTS_DIR/docker-stats.csv"
echo "ts,name,cpu_pct,mem_mib,net_in_mib,net_out_mib,blk_in_mib,blk_out_mib" > "$STATS_FILE"
./scripts/collect-stats.sh "$STATS_FILE" $APPROACH &
STATS_PID=$!

# Start driver — this does the actual run
case "$APPROACH" in
  A) docker compose --profile $APPROACH up -d driver-A ;;
  B) docker compose --profile $APPROACH up -d driver-B ;;
  C) docker compose --profile $APPROACH up -d driver-C ;;
  D) docker compose --profile $APPROACH up -d driver-D ;;
  E) docker compose --profile $APPROACH up -d driver-E ;;
esac

DRIVER="bench-driver-$APPROACH"
CONSUMER_SVC="consumer-$APPROACH"
CONSUMER_C="bench-consumer-$APPROACH"

# Wait for driver to finish (its "Exited" status signals duration end)
echo "--- driver running for ~${DURATION_S}s ---"
docker wait $DRIVER >/dev/null
echo "--- driver done ---"

# Wait for consumer to hit its own natural deadline (DURATION_S + grace) and
# write the summary. The consumer's deadline is set in the container env.
echo "--- waiting for consumer to drain & write summary ---"
docker wait $CONSUMER_C >/dev/null 2>&1 || true

# Stop stats collector
kill $STATS_PID 2>/dev/null || true
wait $STATS_PID 2>/dev/null || true

# Capture MinIO storage metrics for C/D
case "$APPROACH" in
  C|D)
    BUCKET=$( [ "$APPROACH" = "C" ] && echo bench-c-avro || echo bench-d-parquet )
    docker run --rm --network bench-net minio/mc:latest /bin/sh -c "
      mc alias set local http://minio:9000 bench benchpass >/dev/null &&
      mc du --recursive local/$BUCKET 2>/dev/null
      mc ls --recursive local/$BUCKET | wc -l
    " > "$RESULTS_DIR/minio-stats.txt" || true
    ;;
esac

# Save container logs for debugging
mkdir -p "$RESULTS_DIR/logs"
for c in $SERVICES; do
  cname="bench-$c"
  docker logs "$cname" > "$RESULTS_DIR/logs/$cname.log" 2>&1 || true
done

# Tear down per-approach services (keep core services up across runs is fine,
# but we explicitly tear down to make repeated runs deterministic)
docker compose --profile $APPROACH down -v --remove-orphans

echo "=== bench/$APPROACH complete; results in $RESULTS_DIR ==="
