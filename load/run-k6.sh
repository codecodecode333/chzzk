#!/usr/bin/env sh
# Runs load/k6-public-reads.js through the pinned grafana/k6 image. Extra arguments go to `k6 run`.
#   VUS=80 HOLD=3m load/run-k6.sh
#   BASE_URL=https://192.168.0.10:8443 load/run-k6.sh
set -eu
cd "$(dirname "$0")"
K6_IMAGE=grafana/k6:1.3.0
# MSYS_NO_PATHCONV keeps Git Bash from rewriting /scripts into a Windows path.
MSYS_NO_PATHCONV=1 exec docker run --rm -i \
  --add-host=host.docker.internal:host-gateway \
  -e BASE_URL="${BASE_URL:-https://host.docker.internal:8443}" \
  -e VUS="${VUS:-60}" -e RAMP="${RAMP:-30s}" -e HOLD="${HOLD:-3m}" \
  -v "$(pwd):/scripts:ro" \
  "$K6_IMAGE" run "$@" /scripts/k6-public-reads.js
