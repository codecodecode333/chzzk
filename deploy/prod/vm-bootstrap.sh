#!/usr/bin/env bash
# One-time setup of the production VM (Ubuntu 24.04, aarch64 or x86_64; ADR-0006). Runs ON the VM as root, from the
# checkout that deploy.sh pushed; deploy.sh --bootstrap runs it for you:
#
#   sudo bash ~/chzzk/deploy/prod/vm-bootstrap.sh <public-ip>
#
# 1. Docker Engine + Compose plugin from Docker's apt repository; the login user joins the docker group.
# 2. Firewall: the Ubuntu image's iptables rejects everything but SSH; open 80/tcp, 443/tcp, 8189/udp, 8189/tcp
#    (now and in /etc/iptables/rules.v4, so it survives reboots). The OCI security list is deploy/oci/provision.sh's.
# 3. deploy/prod/.env from .env.example, mode 600, with PUBLIC_IP and fresh random secrets. Kept if it exists.
# Safe to re-run: each step skips what is already done.
set -euo pipefail

log() { printf '[bootstrap] %s\n' "$*" >&2; }
[[ $EUID -eq 0 ]] || { log "run as root (sudo)"; exit 1; }
PUBLIC_IP="${1:?usage: vm-bootstrap.sh <public-ip>}"
PROD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OWNER="${SUDO_USER:-ubuntu}"
export DEBIAN_FRONTEND=noninteractive

# ---- 1. Docker -------------------------------------------------------------------------
if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
  log "installing Docker Engine and the Compose plugin"
  apt-get update -qq
  apt-get install -y -qq ca-certificates curl
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  # shellcheck disable=SC1091
  codename="$(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")"
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $codename stable" \
    >/etc/apt/sources.list.d/docker.list
  apt-get update -qq
  apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
else
  log "Docker already installed"
fi
if [[ ! -f /etc/docker/daemon.json ]]; then
  # Bounded container logs, so a chatty service can't fill the boot volume.
  printf '{\n  "log-driver": "local",\n  "log-opts": { "max-size": "20m", "max-file": "5" }\n}\n' >/etc/docker/daemon.json
  systemctl restart docker
fi
systemctl enable --now docker >/dev/null
usermod -aG docker "$OWNER"

# A small shape (E2.1.Micro fallback, 1 GB) can't build the images without swap.
if (( $(awk '/MemTotal/ {print $2}' /proc/meminfo) < 4000000 )) && ! swapon --show | grep -q .; then
  log "low memory: adding a 4 GB swap file"
  fallocate -l 4G /swapfile && chmod 600 /swapfile && mkswap /swapfile >/dev/null && swapon /swapfile
  grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >>/etc/fstab
fi

# ---- 2. Firewall -------------------------------------------------------------------------
RULES=/etc/iptables/rules.v4
open_port() {  # open_port <tcp|udp> <port>
  local rule=(-p "$1" -m "$1" --dport "$2" -j ACCEPT) pos
  if ! iptables -C INPUT "${rule[@]}" 2>/dev/null; then
    # Insert before the image's final REJECT, if there is one.
    pos="$(iptables -L INPUT --line-numbers -n | awk '$2 == "REJECT" {print $1; exit}')"
    if [[ -n "$pos" ]]; then iptables -I INPUT "$pos" "${rule[@]}"; else iptables -A INPUT "${rule[@]}"; fi
    log "opened $2/$1"
  fi
  if [[ -f "$RULES" ]] && ! grep -qxF -- "-A INPUT ${rule[*]}" "$RULES"; then
    # Same place in the saved rules (written directly: saving the live tables would also persist Docker's chains).
    awk -v line="-A INPUT ${rule[*]}" '!done && /^-A INPUT .*-j REJECT/ {print line; done=1} {print}' "$RULES" >"$RULES.new"
    grep -qxF -- "-A INPUT ${rule[*]}" "$RULES.new" || { log "no REJECT rule in $RULES, not persisting $2/$1"; rm "$RULES.new"; return; }
    mv "$RULES.new" "$RULES"
  fi
}
open_port tcp 80
open_port tcp 443
open_port udp 8189
open_port tcp 8189
[[ -f "$RULES" ]] || log "no $RULES: live rules only (nothing on this image persists or blocks them)"

# ---- 3. .env -----------------------------------------------------------------------------
ENV_FILE="$PROD_DIR/.env"
if [[ ! -f "$ENV_FILE" ]]; then
  log "writing $ENV_FILE (mode 600) with fresh secrets"
  ( umask 077
    sed -e "s|^PUBLIC_IP=.*|PUBLIC_IP=$PUBLIC_IP|" \
        -e "s|^POSTGRES_PASSWORD=.*|POSTGRES_PASSWORD=$(openssl rand -hex 24)|" \
        -e "s|^MEDIAMTX_AUTH_SECRET=.*|MEDIAMTX_AUTH_SECRET=$(openssl rand -hex 32)|" \
        "$PROD_DIR/.env.example" >"$ENV_FILE" )
  chown "$OWNER:" "$ENV_FILE"
  log "review it (PUBLIC_HOST, ACME_EMAIL, Toss test keys): nano $ENV_FILE"
else
  chmod 600 "$ENV_FILE"
  log "$ENV_FILE exists, kept"
fi
log "done"
