#!/usr/bin/env bash
# Cluster mode (ADR-0005): creates the single-node kind cluster "chzzk" (if missing), builds and side-loads the backend
# and frontend images, creates the Secret from .env, applies everything and waits until it is ready.
# Run from Git Bash (Windows) or any POSIX shell, with Docker running:  deploy/up.sh
# Re-running it rebuilds the images and rolls the pods; the cluster, database and 영상 파일 are kept.
set -euo pipefail

CLUSTER="${CLUSTER:-chzzk}"
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"
cd "$root"
k() { kubectl --context "kind-$CLUSTER" "$@"; }
step() { printf '\n==> %s\n' "$*"; }

docker info > /dev/null 2>&1 || { echo "Docker is not running." >&2; exit 1; }
command -v kubectl > /dev/null || { echo "kubectl not found (Docker Desktop ships one)." >&2; exit 1; }

# kind: deploy/.tools (fetched and checksum-verified on first use), else one on PATH (e.g. winget install Kubernetes.kind).
. "$here/kind-path.sh"
find_kind fetch

if "$kind" get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
  step "Cluster $CLUSTER exists"
else
  step "Creating cluster $CLUSTER"
  "$kind" create cluster --name "$CLUSTER" --config "$here/kind-cluster.yaml" --wait 120s
fi

step "Building images"
docker build -t chzzk-backend:cluster backend
docker build -t chzzk-frontend:cluster frontend
step "Loading images into the cluster"
"$kind" load docker-image --name "$CLUSTER" chzzk-backend:cluster chzzk-frontend:cluster

k apply -f "$here/k8s/00-namespace.yaml"

step "Secret chzzk-env from .env"
# Read KEY=VALUE lines from .env without executing it.
env_value() {
  [ -f "$root/.env" ] || return 0
  grep -E "^[[:space:]]*$1[[:space:]]*=" "$root/.env" | tail -n 1 | cut -d= -f2- | tr -d '\r' \
    | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/"
}
secret="$(env_value MEDIAMTX_AUTH_SECRET)"
if [ -z "$secret" ]; then
  # No .env value: keep the cluster's current one, else generate a random one (only ever stored in the cluster).
  secret="$(k -n chzzk get secret chzzk-env -o jsonpath='{.data.MEDIAMTX_AUTH_SECRET}' 2>/dev/null | base64 -d 2>/dev/null || true)"
  [ -n "$secret" ] || secret="$(openssl rand -hex 32 2>/dev/null || od -An -tx1 -N32 /dev/urandom | tr -d ' \n')"
fi
lan_ip="$(env_value LAN_IP)"; lan_ip="${lan_ip:-127.0.0.1}"
args=(--from-literal=MEDIAMTX_AUTH_SECRET="$secret" --from-literal=LAN_IP="$lan_ip")
for key in TOSS_CLIENT_KEY TOSS_SECRET_KEY; do
  value="$(env_value "$key")"
  [ -z "$value" ] || args+=(--from-literal="$key=$value")
done
k -n chzzk create secret generic chzzk-env "${args[@]}" --dry-run=client -o yaml | k apply -f - > /dev/null
echo "LAN_IP=$lan_ip; MEDIAMTX_AUTH_SECRET $( [ -n "$(env_value MEDIAMTX_AUTH_SECRET)" ] && echo 'from .env' || echo 'generated/kept in cluster' )"

k -n chzzk create configmap mediamtx-config --from-file=mediamtx.yml=mediamtx/mediamtx.yml \
  --dry-run=client -o yaml | k apply -f -

step "Applying manifests"
existing="$(k -n chzzk get deployments -o name 2>/dev/null || true)"
k apply -f "$here/k8s/metrics-server/components.yaml"
k apply -f "$here/k8s/"
# Same image tags on every run: restart what already existed so it picks up the new images, Secret and config.
for d in backend frontend mediamtx; do
  if grep -qx "deployment.apps/$d" <<< "$existing"; then k -n chzzk rollout restart "deployment/$d"; fi
done

step "Waiting until ready"
k -n chzzk rollout status statefulset/postgres --timeout=300s
k -n chzzk rollout status deployment/mediamtx --timeout=300s
k -n chzzk rollout status deployment/backend --timeout=600s
k -n chzzk rollout status deployment/frontend --timeout=300s
k -n kube-system rollout status deployment/metrics-server --timeout=300s

step "Ready: https://localhost:8443 (self-signed certificate)"
k -n chzzk get pods -o wide
echo
echo "HPA (a CPU reading appears ~1 min after metrics-server starts):"
k -n chzzk get hpa backend
