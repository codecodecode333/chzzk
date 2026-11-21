#!/usr/bin/env bash
# Downloads kind from the official kubernetes-sigs/kind GitHub release into deploy/.tools/ (git-ignored) and checks
# it against the release's published SHA256 checksum. Run from Git Bash:  deploy/fetch-kind.sh
# (Alternative: `winget install Kubernetes.kind`, then up.sh uses the kind on PATH.)
set -euo pipefail

KIND_VERSION="${KIND_VERSION:-v0.33.0}"
here="$(cd "$(dirname "$0")" && pwd)"
tools="$here/.tools"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) asset="kind-windows-amd64"; exe="kind.exe" ;;
  Linux)                asset="kind-linux-amd64";   exe="kind" ;;
  Darwin)               asset="kind-darwin-$( [ "$(uname -m)" = arm64 ] && echo arm64 || echo amd64 )"; exe="kind" ;;
  *) echo "unsupported OS: $(uname -s)" >&2; exit 1 ;;
esac
base="https://github.com/kubernetes-sigs/kind/releases/download/$KIND_VERSION"

mkdir -p "$tools"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
curl -fsSL -o "$tmp/$asset" "$base/$asset"
curl -fsSL -o "$tmp/$asset.sha256sum" "$base/$asset.sha256sum"

expected="$(awk '{print $1}' "$tmp/$asset.sha256sum" | tr 'A-F' 'a-f')"
actual="$(sha256sum "$tmp/$asset" | awk '{print $1}')"
if [ -z "$expected" ] || [ "$expected" != "$actual" ]; then
  echo "SHA256 mismatch for $asset: expected '$expected', got '$actual'" >&2
  exit 1
fi
mv "$tmp/$asset" "$tools/$exe"
chmod +x "$tools/$exe"
echo "kind $KIND_VERSION verified (sha256 $actual) -> $tools/$exe"
