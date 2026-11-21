#!/usr/bin/env bash
# Deletes the kind cluster created by deploy/up.sh, with its database and 영상 파일. Run from Git Bash: deploy/down.sh
set -euo pipefail

CLUSTER="${CLUSTER:-chzzk}"
here="$(cd "$(dirname "$0")" && pwd)"
kind="$here/.tools/kind.exe"; [ -x "$kind" ] || kind="$here/.tools/kind"
[ -x "$kind" ] || kind="$(command -v kind || true)"
[ -n "$kind" ] || { echo "kind not found (run deploy/fetch-kind.sh)." >&2; exit 1; }

"$kind" delete cluster --name "$CLUSTER"
