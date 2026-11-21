# 04: kind 클러스터, 공용 상태, Service, HPA

**What to build:** One script creates a single-node kind cluster and deploys the whole service: PostgreSQL ×1 with a PVC, MediaMTX ×1, the backend Deployment with a shared PVC for 영상 파일, the nginx entry point on `https://localhost:8443`, metrics-server, and an HPA (CPU 50%, min 2, max 5, scale-down stabilization 60s). Another script tears it down. Secrets come from `.env` by script. See spec and ADR-0005.

**Blocked by:** 01, 03

**Status:** ready-for-agent

- [ ] `kind.exe` fetched from the official GitHub release with SHA256 verified into a gitignored folder (README also mentions `winget install Kubernetes.kind`)
- [ ] kind config in the repo: host port 8443 → nginx, UDP 8189 → MediaMTX media; `LAN_IP` honoured for MediaMTX
- [ ] Manifests: PostgreSQL StatefulSet + PVC; MediaMTX Deployment (1 replica) with the auth callback to the backend Service and the shared secret; backend Deployment (CPU request 500m, readiness/liveness probes, termination grace > shutdown timeout, shared 영상 파일 PVC, env from Secret) + Service; nginx Deployment + Service; metrics-server pinned manifest stored in the repo with `--kubelet-insecure-tls`; HPA as above
- [ ] Secret built from `.env` (MEDIAMTX_AUTH_SECRET, TOSS keys if set, LAN_IP); nothing secret committed
- [ ] `up` script: create cluster, build and load images, apply, wait until ready; `down` script deletes the cluster
- [ ] Manual check: signup/login, register a VOD and play it, start/see a 방송 in the list, write a 댓글, all through `https://localhost:8443`, with responses coming from at least two different `X-Served-By`; a VOD registered via one pod plays via another
- [ ] `kubectl get hpa` shows a CPU reading (metrics-server works)
