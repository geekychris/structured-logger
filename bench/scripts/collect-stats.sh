#!/usr/bin/env bash
# Sample docker stats every N seconds while a run is in flight. Filters by approach.
set -euo pipefail
OUT="${1:?usage: collect-stats.sh <out.csv> <approach>}"
APPROACH="${2:?}"
INTERVAL="${INTERVAL_S:-5}"

# Match container names for the chosen approach. Match pattern includes core services
# we care about (kafka or minio depending on approach) plus per-approach drivers/consumers.
pattern="bench-(driver|consumer|sidecar)-$APPROACH"
case "$APPROACH" in
  A|B) pattern="$pattern|bench-kafka" ;;
  C|D) pattern="$pattern|bench-minio$" ;;
  E)   pattern="$pattern|bench-warpstream|bench-minio$" ;;
esac
[ "$APPROACH" = "B" ] && pattern="$pattern|bench-schema-registry"

while true; do
  ts=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  # docker stats: name, CPU %, mem (used / lim), net I/O, block I/O
  docker stats --no-stream --format '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}|{{.NetIO}}|{{.BlockIO}}' 2>/dev/null \
    | grep -E "$pattern" \
    | awk -v ts="$ts" -F'|' '
      function parse_size(s,   n,unit) {
        gsub(/ /,"",s); n = s+0
        if (s ~ /GiB|GB/) return n*1024
        if (s ~ /MiB|MB/) return n
        if (s ~ /KiB|KB/) return n/1024
        if (s ~ /B$/)     return n/1024/1024
        return n
      }
      {
        name=$1
        gsub(/%/,"",$2); cpu=$2+0
        split($3, m, " / "); mem=parse_size(m[1])
        split($4, n, " / "); netin=parse_size(n[1]); netout=parse_size(n[2])
        split($5, b, " / "); blkin=parse_size(b[1]); blkout=parse_size(b[2])
        printf "%s,%s,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f\n", ts,name,cpu,mem,netin,netout,blkin,blkout
      }' >> "$OUT"
  sleep "$INTERVAL"
done
