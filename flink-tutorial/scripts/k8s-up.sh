#!/usr/bin/env bash
# Apply all k8s manifests, wait for ready, print access info.
set -euo pipefail
cd "$(dirname "$0")/.."

kubectl apply -f k8s/
echo "Waiting for jobmanager to be ready..."
kubectl -n flink-tutorial rollout status deploy/flink-jobmanager --timeout=180s
echo "Waiting for taskmanager to be ready..."
kubectl -n flink-tutorial rollout status deploy/flink-taskmanager --timeout=180s

echo
echo "Cluster is up. Access options:"
echo "  • NodePort UI:        http://localhost:30030  (Rancher/Docker Desktop forwards NodePort)"
echo "  • Port-forward UI:    kubectl -n flink-tutorial port-forward svc/flink-jobmanager 18030:8081"
echo "  • Port-forward JM JDWP: kubectl -n flink-tutorial port-forward svc/flink-jobmanager 18040:5005"
echo "  • Port-forward TM JDWP: kubectl -n flink-tutorial port-forward svc/flink-taskmanager 18041:5005"
echo
echo "Submit a jar:"
echo "  ./scripts/k8s-submit.sh com.example.flink.HelloTableApi"
