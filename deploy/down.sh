#!/usr/bin/env bash
# Deletes the kind cluster created by deploy/up.sh, with its database and 영상 파일. Run from Git Bash: deploy/down.sh
set -euo pipefail

CLUSTER="${CLUSTER:-chzzk}"
here="$(cd "$(dirname "$0")" && pwd)"
. "$here/kind-path.sh"
find_kind

"$kind" delete cluster --name "$CLUSTER"
