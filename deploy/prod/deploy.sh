#!/usr/bin/env bash
# Deploy or update the production VM from the operator's PC (ADR-0006). Run from Git Bash or any POSIX shell:
#
#   deploy/prod/deploy.sh --bootstrap <vm-ip>   # first time: also installs Docker, opens the firewall, writes .env
#   deploy/prod/deploy.sh <vm-ip>               # every release after that
#
# Pushes the committed DEPLOY_REF (default HEAD) over SSH into ~/chzzk on the VM (branch `deploy`, checked out in
# place: no GitHub access needed there), then runs `docker compose up --build -d` in deploy/prod and waits until the
# backend's /healthz says UP. The VM keeps its own deploy/prod/.env (git-ignored) across deploys.
#
# Settings (environment): SSH_KEY (default ~/.ssh/chzzk_oci), VM_USER (ubuntu), DEPLOY_REF (HEAD),
# HEALTH_TIMEOUT seconds (300).
set -euo pipefail

log() { printf '[deploy] %s\n' "$*" >&2; }
BOOTSTRAP=0
if [[ "${1:-}" == "--bootstrap" ]]; then BOOTSTRAP=1; shift; fi
VM="${1:?usage: deploy.sh [--bootstrap] <vm-ip>}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/chzzk_oci}"
VM_USER="${VM_USER:-ubuntu}"
DEPLOY_REF="${DEPLOY_REF:-HEAD}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-300}"
REMOTE_DIR=chzzk   # relative to the VM user's home

[[ -f "$SSH_KEY" ]] || { log "SSH key not found: $SSH_KEY"; exit 1; }
SSH_OPTS=(-i "$SSH_KEY" -o StrictHostKeyChecking=accept-new -o BatchMode=yes -o ConnectTimeout=15)
ssh_vm() { ssh "${SSH_OPTS[@]}" "$VM_USER@$VM" "$@"; }

cd "$(git rev-parse --show-toplevel)"
commit="$(git rev-parse --verify "$DEPLOY_REF^{commit}")"
[[ -z "$(git status --porcelain)" ]] || log "warning: uncommitted changes are not deployed (only $DEPLOY_REF)"
log "deploying $(git log -1 --format='%h %s' "$commit") to $VM_USER@$VM"

# The VM's checkout: a plain repository whose `deploy` branch is updated in place by each push.
ssh_vm "set -e
  command -v git >/dev/null || { sudo apt-get update -qq && sudo apt-get install -y -qq git; }
  if [ ! -d $REMOTE_DIR/.git ]; then
    git init -q -b deploy $REMOTE_DIR
    git -C $REMOTE_DIR config receive.denyCurrentBranch updateInstead
  fi"
GIT_SSH_COMMAND="ssh $(printf '%q ' "${SSH_OPTS[@]}")" git push --quiet --force "$VM_USER@$VM:$REMOTE_DIR" "$commit:refs/heads/deploy"
ssh_vm "test \"\$(git -C $REMOTE_DIR rev-parse HEAD)\" = $commit" || { log "VM checkout is not at $commit"; exit 1; }

if (( BOOTSTRAP )); then
  ssh_vm "sudo bash $REMOTE_DIR/deploy/prod/vm-bootstrap.sh $VM"
fi

# A fresh SSH session, so a just-added docker group membership applies.
ssh_vm "set -e
  cd $REMOTE_DIR/deploy/prod
  if [ ! -f .env ]; then echo '[deploy] no deploy/prod/.env on the VM: run with --bootstrap first' >&2; exit 1; fi
  if grep -q '=change-me\$' .env; then echo '[deploy] deploy/prod/.env still has change-me values' >&2; exit 1; fi
  docker compose up --build -d --remove-orphans
  docker image prune -f >/dev/null"

log "waiting up to ${HEALTH_TIMEOUT}s for /healthz"
ssh_vm "cd $REMOTE_DIR/deploy/prod
  for i in \$(seq 1 $((HEALTH_TIMEOUT / 5))); do
    if docker compose exec -T caddy wget -qO- http://backend:8080/healthz 2>/dev/null | grep -q '\"UP\"'; then
      echo '[deploy] backend /healthz: UP' >&2; exit 0
    fi
    sleep 5
  done
  echo '[deploy] backend did not report UP in time; recent logs:' >&2
  docker compose ps >&2; docker compose logs --tail 40 backend >&2
  exit 1"

# From here, through DNS, Caddy and its certificate (fails until the A record and the certificate exist).
host="$(ssh_vm "sed -n 's/^PUBLIC_HOST=//p' $REMOTE_DIR/deploy/prod/.env")"
if out="$(curl -fsS --max-time 15 "https://$host/healthz" 2>&1)"; then
  log "https://$host/healthz: $out"
else
  log "warning: https://$host/healthz not reachable from here yet ($out). DNS A record -> $VM? Caddy logs:"
  log "  ssh -i $SSH_KEY $VM_USER@$VM 'cd $REMOTE_DIR/deploy/prod && docker compose logs caddy'"
fi
