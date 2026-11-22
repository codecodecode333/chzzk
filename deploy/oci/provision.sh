#!/usr/bin/env bash
# Create the network and one VM for the public deploy (ADR-0006), then print its public IP.
#
#   deploy/oci/provision.sh [--dry-run]
#
# Settings: environment or deploy/oci/.env (see .env.example). Nothing here holds OCIDs.
# Safe to re-run: resources named "$NAME_PREFIX-*" that already exist are reused, and an
# existing running VM is reported instead of launching another.
#
# Network: VCN 10.0.0.0/16, public subnet 10.0.0.0/24, internet gateway, route table
# (0.0.0.0/0 -> IGW), security list: 80/tcp, 443/tcp, 8189/udp, 8189/tcp from anywhere,
# 22/tcp only from SSH_ALLOW_CIDR; all egress.
#
# VM fallback order (spec): A1.Flex 2 OCPU/12 GB in each AD -> A1.Flex 1 OCPU/6 GB in each AD
# -> retry both for RETRY_MINUTES -> E2.1.Micro only if ALLOW_E2_MICRO=1.
# Image: latest Canonical Ubuntu 24.04 built for the shape (aarch64 for A1).
source "$(dirname "$0")/lib.sh"

DRY_RUN=0
[[ "${1:-}" == "--dry-run" ]] && DRY_RUN=1

SSH_PUBKEY="${SSH_PUBKEY:-$HOME/.ssh/chzzk_oci.pub}"
SSH_PUBKEY="${SSH_PUBKEY/#\~/$HOME}"
RETRY_MINUTES="${RETRY_MINUTES:-0}"
RETRY_INTERVAL_SECONDS="${RETRY_INTERVAL_SECONDS:-120}"
ALLOW_E2_MICRO="${ALLOW_E2_MICRO:-0}"
BOOT_VOLUME_GB="${BOOT_VOLUME_GB:-50}"
INSTANCE_NAME="$NAME_PREFIX-vm"

[[ -f "$SSH_PUBKEY" ]] || { log "public key not found: $SSH_PUBKEY"; exit 1; }
if [[ -z "${SSH_ALLOW_CIDR:-}" ]]; then
  SSH_ALLOW_CIDR="$(curl -fsS https://checkip.amazonaws.com | tr -d '[:space:]')/32"
fi
if [[ -z "${OCI_ADS:-}" ]]; then
  OCI_ADS="$(oci_cmd iam availability-domain list -c "$OCI_COMPARTMENT_ID" --query 'data[].name' --raw-output | tr -d '[]",' | xargs)"
fi
log "compartment set, ADs: $OCI_ADS, SSH from: $SSH_ALLOW_CIDR"

run() {  # run a mutating command, or show it in --dry-run
  if (( DRY_RUN )); then log "DRY-RUN: oci $*"; echo "ocid1.dryrun.$NAME_PREFIX"; else oci_cmd "$@"; fi
}

# ---- network -------------------------------------------------------------------------
ensure_network() {
  local vcn igw rt sl subnet
  vcn="$(vcn_id)"
  if [[ -z "$vcn" ]]; then
    log "creating VCN $NAME_PREFIX-vcn"
    vcn="$(run network vcn create -c "$OCI_COMPARTMENT_ID" --display-name "$NAME_PREFIX-vcn" \
      --cidr-blocks "$(json_arg cidrs '["10.0.0.0/16"]')" --dns-label "$NAME_PREFIX" \
      --wait-for-state AVAILABLE --query 'data.id' --raw-output)"
  else log "reusing VCN $NAME_PREFIX-vcn"; fi

  igw="$(find_id network internet-gateway list -c "$OCI_COMPARTMENT_ID" --vcn-id "$vcn" --display-name "$NAME_PREFIX-igw")"
  if [[ -z "$igw" ]]; then
    log "creating internet gateway"
    igw="$(run network internet-gateway create -c "$OCI_COMPARTMENT_ID" --vcn-id "$vcn" \
      --display-name "$NAME_PREFIX-igw" --is-enabled true \
      --wait-for-state AVAILABLE --query 'data.id' --raw-output)"
  fi

  rt="$(find_id network route-table list -c "$OCI_COMPARTMENT_ID" --vcn-id "$vcn" --display-name "$NAME_PREFIX-rt")"
  if [[ -z "$rt" ]]; then
    log "creating route table"
    rt="$(run network route-table create -c "$OCI_COMPARTMENT_ID" --vcn-id "$vcn" --display-name "$NAME_PREFIX-rt" \
      --route-rules "$(json_arg routes "[{\"networkEntityId\":\"$igw\",\"destination\":\"0.0.0.0/0\",\"destinationType\":\"CIDR_BLOCK\"}]")" \
      --wait-for-state AVAILABLE --query 'data.id' --raw-output)"
  fi

  local tcp_any='"protocol":"6","source":"0.0.0.0/0","sourceType":"CIDR_BLOCK"'
  local ingress="[
    {$tcp_any,\"tcpOptions\":{\"destinationPortRange\":{\"min\":80,\"max\":80}},\"description\":\"http\"},
    {$tcp_any,\"tcpOptions\":{\"destinationPortRange\":{\"min\":443,\"max\":443}},\"description\":\"https\"},
    {$tcp_any,\"tcpOptions\":{\"destinationPortRange\":{\"min\":8189,\"max\":8189}},\"description\":\"webrtc tcp\"},
    {\"protocol\":\"17\",\"source\":\"0.0.0.0/0\",\"sourceType\":\"CIDR_BLOCK\",\"udpOptions\":{\"destinationPortRange\":{\"min\":8189,\"max\":8189}},\"description\":\"webrtc udp\"},
    {\"protocol\":\"6\",\"source\":\"$SSH_ALLOW_CIDR\",\"sourceType\":\"CIDR_BLOCK\",\"tcpOptions\":{\"destinationPortRange\":{\"min\":22,\"max\":22}},\"description\":\"ssh operator\"},
    {\"protocol\":\"1\",\"source\":\"0.0.0.0/0\",\"sourceType\":\"CIDR_BLOCK\",\"icmpOptions\":{\"type\":3,\"code\":4},\"description\":\"path mtu\"}
  ]"
  local egress='[{"protocol":"all","destination":"0.0.0.0/0","destinationType":"CIDR_BLOCK"}]'

  sl="$(find_id network security-list list -c "$OCI_COMPARTMENT_ID" --vcn-id "$vcn" --display-name "$NAME_PREFIX-sl")"
  if [[ -z "$sl" ]]; then
    log "creating security list"
    sl="$(run network security-list create -c "$OCI_COMPARTMENT_ID" --vcn-id "$vcn" --display-name "$NAME_PREFIX-sl" \
      --ingress-security-rules "$(json_arg ingress "$ingress")" \
      --egress-security-rules "$(json_arg egress "$egress")" \
      --wait-for-state AVAILABLE --query 'data.id' --raw-output)"
  else
    log "updating security list rules (SSH from $SSH_ALLOW_CIDR)"
    run network security-list update --security-list-id "$sl" --force \
      --ingress-security-rules "$(json_arg ingress "$ingress")" \
      --egress-security-rules "$(json_arg egress "$egress")" \
      --wait-for-state AVAILABLE >/dev/null
  fi

  subnet="$(find_id network subnet list -c "$OCI_COMPARTMENT_ID" --vcn-id "$vcn" --display-name "$NAME_PREFIX-subnet")"
  if [[ -z "$subnet" ]]; then
    log "creating public subnet"
    subnet="$(run network subnet create -c "$OCI_COMPARTMENT_ID" --vcn-id "$vcn" --display-name "$NAME_PREFIX-subnet" \
      --cidr-block 10.0.0.0/24 --dns-label pub --route-table-id "$rt" \
      --security-list-ids "$(json_arg sls "[\"$sl\"]")" --prohibit-public-ip-on-vnic false \
      --wait-for-state AVAILABLE --query 'data.id' --raw-output)"
  fi
  SUBNET_ID="$subnet"
}

# ---- instance ------------------------------------------------------------------------
latest_ubuntu_image() {  # $1 = shape
  oci_cmd compute image list -c "$OCI_COMPARTMENT_ID" --operating-system "Canonical Ubuntu" \
    --operating-system-version "24.04" --shape "$1" --sort-by TIMECREATED --sort-order DESC --all \
    --query 'data[0].id' --raw-output
}

print_ip() {  # $1 = instance id
  local ip
  ip="$(oci_cmd compute instance list-vnics --instance-id "$1" --query 'data[0]."public-ip"' --raw-output)"
  log "VM $INSTANCE_NAME public IP: $ip"
  echo "$ip"
}

# Try one launch. Returns 0 on success, 2 on a capacity miss (try the next option), 1 on any other error.
try_launch() {  # $1 = AD, $2 = shape, $3 = ocpus (empty for fixed shapes), $4 = memory GB
  local ad="$1" shape="$2" image out rc=0
  local -a shape_args=()
  [[ -n "$3" ]] && shape_args=(--shape-config "$(json_arg shapecfg "{\"ocpus\":$3,\"memoryInGBs\":$4}")")
  image="$(latest_ubuntu_image "$shape")"
  log "launching $shape ${3:+$3 OCPU/$4 GB} in $ad (image $(oci_cmd compute image get --image-id "$image" --query 'data."display-name"' --raw-output))"
  if (( DRY_RUN )); then log "DRY-RUN: skip launch"; return 2; fi
  out="$(oci_cmd compute instance launch -c "$OCI_COMPARTMENT_ID" --availability-domain "$ad" \
    --display-name "$INSTANCE_NAME" --shape "$shape" "${shape_args[@]}" --image-id "$image" \
    --boot-volume-size-in-gbs "$BOOT_VOLUME_GB" --subnet-id "$SUBNET_ID" --assign-public-ip true \
    --ssh-authorized-keys-file "$(native_path "$SSH_PUBKEY")" \
    --wait-for-state RUNNING --max-wait-seconds 1200 --query 'data.id' --raw-output 2>&1)" || rc=$?
  if (( rc == 0 )) && [[ "$out" =~ (ocid1\.instance\.[^[:space:]]+) ]]; then
    INSTANCE_ID="${BASH_REMATCH[1]}"; return 0
  fi
  if grep -qiE 'out of (host )?capacity|InternalError|TooManyRequests' <<<"$out"; then
    log "capacity miss: $(grep -m1 -oiE '"message": "[^"]*"' <<<"$out" || echo 'see output')"
    return 2
  fi
  log "launch failed:"; printf '%s\n' "$out" >&2
  return 1
}

launch_pass() {  # $1 = shape, $2 = ocpus, $3 = memory; tries every AD
  local ad rc
  for ad in $OCI_ADS; do
    rc=0; try_launch "$ad" "$1" "$2" "$3" || rc=$?
    (( rc == 0 )) && return 0
    (( rc == 1 )) && exit 1
  done
  return 2
}

main() {
  local existing
  existing="$(find_id compute instance list -c "$OCI_COMPARTMENT_ID" --display-name "$INSTANCE_NAME")"
  ensure_network
  if [[ -n "$existing" ]]; then
    log "instance $INSTANCE_NAME already exists; not launching another"
    print_ip "$existing"; return
  fi

  local deadline=$(( $(date +%s) + RETRY_MINUTES * 60 )) attempt=1
  while :; do
    launch_pass VM.Standard.A1.Flex 2 12 && break
    launch_pass VM.Standard.A1.Flex 1 6 && break
    if (( $(date +%s) < deadline )); then
      log "no A1 capacity (attempt $attempt); retrying in ${RETRY_INTERVAL_SECONDS}s"
      sleep "$RETRY_INTERVAL_SECONDS"; attempt=$((attempt + 1)); continue
    fi
    if [[ "$ALLOW_E2_MICRO" == "1" ]]; then
      log "A1 unavailable; falling back to E2.1.Micro (last resort)"
      launch_pass VM.Standard.E2.1.Micro "" "" && break
    fi
    (( DRY_RUN )) && { log "DRY-RUN complete"; return; }
    log "no capacity for any allowed shape"; exit 3
  done
  print_ip "$INSTANCE_ID"
}

main
