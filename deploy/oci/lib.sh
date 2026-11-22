# Shared helpers for deploy/oci/*.sh (sourced, not run).
# Settings come from the environment, or from deploy/oci/.env (git-ignored; see .env.example).

set -euo pipefail

OCI_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -f "$OCI_DIR/.env" ]]; then
  # shellcheck disable=SC1091
  set -a; source "$OCI_DIR/.env"; set +a
fi

: "${OCI_COMPARTMENT_ID:?set OCI_COMPARTMENT_ID (the tenancy OCID works for the root compartment)}"
NAME_PREFIX="${NAME_PREFIX:-chzzk}"
OCI_BIN="${OCI_BIN:-oci}"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

export SUPPRESS_LABEL_WARNING=True OCI_CLI_SUPPRESS_FILE_PERMISSIONS_WARNING=True PYTHONWARNINGS=ignore

log() { printf '[oci] %s\n' "$*" >&2; }

# The OCI CLI is a Windows program under Git Bash: hand it native paths.
native_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

oci_cmd() { "$OCI_BIN" "$@"; }

# Write JSON to a temp file and return a file:// reference (avoids shell quoting of JSON args).
json_arg() {
  local name="$1" body="$2" f="$WORK_DIR/$1.json"
  printf '%s' "$body" >"$f"
  printf 'file://%s' "$(native_path "$f")"
}

# First OCID of a resource with our display name that is not terminated/deleted, or empty.
find_id() {
  # usage: find_id <oci list args...>   (must return data[] with id, lifecycle-state)
  oci_cmd "$@" --all \
    --query 'data[?!(contains(`["TERMINATED","TERMINATING","DELETED","DELETING"]`, "lifecycle-state"))] | [0].id' \
    --raw-output 2>/dev/null | grep '^ocid1' || true
}

vcn_id() { find_id network vcn list -c "$OCI_COMPARTMENT_ID" --display-name "$NAME_PREFIX-vcn"; }
