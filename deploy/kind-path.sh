# Sourced by up.sh and down.sh (not run on its own): sets $kind to the kind binary, deploy/.tools first (fetched and
# checksum-verified by fetch-kind.sh), else one on PATH. With "fetch", downloads it when neither exists.
# Expects $here = the deploy/ directory.
find_kind() {
  kind="$here/.tools/kind.exe"; [ -x "$kind" ] || kind="$here/.tools/kind"
  [ -x "$kind" ] && return 0
  if command -v kind > /dev/null; then kind="$(command -v kind)"; return 0; fi
  if [ "${1:-}" = fetch ]; then
    printf '\n==> Fetching kind\n'
    "$here/fetch-kind.sh"
    kind="$here/.tools/kind.exe"; [ -x "$kind" ] || kind="$here/.tools/kind"
    return 0
  fi
  echo "kind not found (run deploy/fetch-kind.sh)." >&2
  return 1
}
