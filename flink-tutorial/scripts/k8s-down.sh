#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
kubectl delete -f k8s/ --ignore-not-found
