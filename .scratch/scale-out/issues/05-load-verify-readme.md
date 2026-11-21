# 05: 부하·유지 확인 스크립트와 README 증거

**What to build:** A k6 load script (run through the `grafana/k6` Docker image, no install) drives the cluster so the backend scales out, and after it stops the backend scales back in. A verification script holds one logged-in session and, every few seconds while scaling happens, prints time, `/api/members/me` result, 방송 count, 댓글 count on a chosen 방송, `X-Served-By`, and the current backend pod count. Run both for real and put the captured output in the README, along with how to run cluster mode and its limits. See spec and ADR-0005.

**Blocked by:** 02, 04

**Status:** resolved

- [x] k6 script: ramp-up, ~3 min hold, stop; mixed public reads (방송 list, VOD list, 댓글); target overridable; self-signed TLS accepted
- [x] Verification script with the per-tick output above; seeds its own 회원, 방송 and 댓글 if needed
- [x] Real run: backend pods go from 2 up (≥3) under load and back to 2 after; login stays valid, 방송 list and 댓글 unchanged throughout; requests served by several instances
- [x] README section "클러스터 모드 (자동 확장)": prerequisites, up/down, load, verify, the captured `kubectl get hpa -w` (or pod count) output and verification output, why PostgreSQL/MediaMTX aren't scaled, the single-node shared-volume limit, how to tune CPU request/target
- [x] README 공개 범위/API docs mention the health endpoints and `X-Served-By`
