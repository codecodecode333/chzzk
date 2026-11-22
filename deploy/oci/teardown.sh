#!/usr/bin/env bash
# Delete everything provision.sh created (VM + boot volume, subnet, security list, route table,
# internet gateway, VCN), found by the "$NAME_PREFIX-*" display names.
#
#   deploy/oci/teardown.sh --yes
#
# Settings: environment or deploy/oci/.env (see .env.example).
source "$(dirname "$0")/lib.sh"

[[ "${1:-}" == "--yes" ]] || { log "this deletes the VM and its disk; re-run with --yes"; exit 1; }

inst="$(find_id compute instance list -c "$OCI_COMPARTMENT_ID" --display-name "$NAME_PREFIX-vm")"
if [[ -n "$inst" ]]; then
  log "terminating instance $NAME_PREFIX-vm (boot volume deleted too)"
  oci_cmd compute instance terminate --instance-id "$inst" --preserve-boot-volume false --force \
    --wait-for-state TERMINATED --max-wait-seconds 1200 >/dev/null
fi

vcn="$(vcn_id)"
if [[ -z "$vcn" ]]; then log "no VCN $NAME_PREFIX-vcn; done"; exit 0; fi

delete_named() {  # $1 = resource group (e.g. "network subnet"), $2 = id flag, $3 = name suffix
  local id
  # shellcheck disable=SC2086
  id="$(find_id $1 list -c "$OCI_COMPARTMENT_ID" --vcn-id "$vcn" --display-name "$NAME_PREFIX-$3")"
  [[ -z "$id" ]] && return 0
  log "deleting $1 $NAME_PREFIX-$3"
  # shellcheck disable=SC2086
  oci_cmd $1 delete "$2" "$id" --force --wait-for-state TERMINATED >/dev/null
}

delete_named "network subnet" --subnet-id subnet
delete_named "network security-list" --security-list-id sl
delete_named "network route-table" --rt-id rt
delete_named "network internet-gateway" --ig-id igw

log "deleting VCN $NAME_PREFIX-vcn"
oci_cmd network vcn delete --vcn-id "$vcn" --force --wait-for-state TERMINATED >/dev/null
log "teardown complete"
