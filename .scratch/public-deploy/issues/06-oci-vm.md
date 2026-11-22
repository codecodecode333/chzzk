# 06: OCI Always Free VM 준비

**What to build:** Install the OCI CLI (official installer), confirm the API key config works, and with read-only calls report the home region, A1 limits and current usage, and availability domains. Show the operator the findings and get agreement before creating anything. Then create one VM per the fallback order (A1.Flex 2 OCPU/12GB in each AD → A1 1 OCPU/6GB → retry script → E2.1.Micro last), Ubuntu 24.04 (aarch64 for A1), public IP, with a new SSH key generated on this PC; set the security list to 80/443 tcp, 8189 udp+tcp, and 22 only from this PC's public IP. Scripts committed without OCIDs or keys. See spec and ADR-0006.

**Blocked by:** None (the operator's OCI API key is in `~/.oci/config`, region ap-singapore-1)

**Status:** ready-for-agent

- [ ] OCI CLI installed; read-only checks recorded (home region, A1 limits/usage, ADs)
- [ ] Operator agreed to the VM creation (asked in chat with the exact shape)
- [ ] VM running and reachable by SSH from this PC; shape, image, AD and public IP recorded
- [ ] Security list matches the ports above; fallback taken (if any) and the reason recorded for the README
