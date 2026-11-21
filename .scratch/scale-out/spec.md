# 스펙: 로컬 클러스터에서 여러 인스턴스로 띄우고 부하에 따라 자동 확장

**Status:** ready-for-agent

## Problem Statement

치지직은 지금 백엔드 하나를 개발 모드(bootRun + Vite)로만 띄운다. 시청자가 몰리면 그 하나가 버티지 못하고, 늘릴 방법도 없다. 백엔드를 여러 개 띄우려 해도 영상 파일이 인스턴스 디스크에 있고, 송출 감시·후원 만료 같은 예약 작업이 인스턴스마다 따로 돌아, 인스턴스가 늘거나 줄 때 무엇이 유지되는지 보장되지 않는다.

## Solution

로컬 kind 클러스터(단일 노드)에서 백엔드를 컨테이너로 2~5개 띄우고, Kubernetes HPA가 CPU 사용률(목표 50%)에 따라 자동으로 늘리고 줄인다. nginx 하나가 HTTPS 진입점(`https://localhost:8443`)이 되어 화면을 내주고 `/api`를 백엔드 Service로, `/live`를 MediaMTX로 넘기며, Service가 백엔드 파드들에 요청을 나눈다. 로그인 세션·VOD·방송·댓글·후원은 클러스터 안의 PostgreSQL, 영상 파일은 모든 백엔드 파드가 함께 쓰는 공유 볼륨에 두어, 인스턴스 수가 바뀌어도 그대로다. 부하 스크립트(k6)와 유지 확인 스크립트로 직접 확인하고 그 결과를 README에 남긴다. 기존 개발 방식은 그대로 둔다. (ADR-0005)

## User Stories

1. As a 개발자, I want to build the backend as a container image with one command, so that it runs the same anywhere.
2. As a 개발자, I want to build the frontend as an nginx image that serves the built screens, so that the cluster doesn't need the Vite dev server.
3. As a 개발자, I want one script that creates the kind cluster and deploys everything, so that I can reproduce the setup from the repo alone.
4. As a 개발자, I want one script that tears the cluster down, so that I can start clean.
5. As a 개발자, I want secrets (MediaMTX 시크릿, 토스 테스트 키) to flow from my `.env` into a Kubernetes Secret by script, so that no secret is committed.
6. As a 시청자, I want to open `https://localhost:8443` and use the whole service, so that cluster mode behaves like dev mode.
7. As a 시청자, I want my requests spread across backend instances, so that no single instance is overloaded.
8. As a 회원, I want to stay logged in when instances are added or removed, so that scaling is invisible to me.
9. As a 회원, I want to stay logged in when my next request lands on a different instance, so that load balancing is invisible to me.
10. As a 소유자, I want a VOD I registered through one instance to play through every other instance, so that 영상 파일 aren't tied to an instance.
11. As a 시청자, I want the 방송 list and 방송 상태 to be the same whichever instance answers, so that 라이브 state is consistent.
12. As a 회원, I want 댓글 to be the same whichever instance answers, so that nothing appears or disappears with scaling.
13. As a 후원자, I want my 후원 and the 후원 합계 to be the same whichever instance answers, so that money records are consistent.
14. As a 운영자, I want the backend count to grow when CPU use goes above 50%, so that load is absorbed.
15. As a 운영자, I want the backend count to shrink back when load stops, so that resources are freed.
16. As a 운영자, I want never fewer than 2 and never more than 5 backend instances, so that there is redundancy and a ceiling.
17. As a 운영자, I want scale-in to settle within about a minute after load stops, so that the demo is short.
18. As a 운영자, I want an instance to receive traffic only once it is ready, so that requests don't hit a starting instance.
19. As a 운영자, I want an instance being removed to finish its in-flight requests first, so that scale-in drops no requests.
20. As a 운영자, I want an instance that stops responding to be restarted, so that the service heals.
21. As a 운영자, I want 송출 감시 to run on only one instance per round, so that MediaMTX isn't queried once per instance.
22. As a 운영자, I want 후원 order expiry to run on only one instance per round, so that the job doesn't race itself.
23. As a 소유자, I want 방송 auto-end after 60s without media to keep working with several instances, so that ghost 방송 still end.
24. As a 개발자, I want each response to say which instance served it, so that I can see the load being spread.
25. As a 개발자, I want health endpoints open without login but no other management endpoints exposed, so that probes work without leaking internals.
26. As a 개발자, I want a k6 load script runnable through Docker without installing anything, so that anyone can apply load.
27. As a 개발자, I want a verification script that keeps one logged-in session and repeatedly reads 내 정보, the 방송 list and a 방송's 댓글 while recording which instance answered and how many instances exist, so that I can prove state survives scaling.
28. As a 리뷰어, I want the README to show real `kubectl get hpa`/pod-count output of instances going up under load and back down after, so that the behaviour is evidenced.
29. As a 리뷰어, I want the README to show the verification script's output during scaling, so that I can see login, 방송 list and 댓글 survived.
30. As a 개발자, I want the existing compose + bootRun + Vite flow untouched, so that day-to-day development is unchanged.
31. As a 소유자, I want 휴대폰 송출 to be possible in cluster mode (LAN IP, UDP media port), so that 라이브 still works there.
32. As a 개발자, I want the README to explain why MediaMTX and PostgreSQL are not scaled and why the shared volume limits the cluster to one node, so that the limits are understood.

## Implementation Decisions

- **Orchestrator:** single-node kind cluster, configuration file in the repo; host port mappings for the HTTPS entry (8443) and the WebRTC UDP media port (8189). `kind.exe` is fetched from the official kubernetes-sigs/kind GitHub release with its SHA256 checksum verified, into a gitignored tool folder; the README also mentions `winget install Kubernetes.kind`. Images are side-loaded into the cluster (no registry).
- **Autoscaling:** Kubernetes HPA (autoscaling/v2) on CPU utilization, target 50%, min 2, max 5, scale-down stabilization 60s. Backend pods request 500m CPU (tunable if load can't move it). metrics-server from its official manifest, version pinned and stored in the repo, with `--kubelet-insecure-tls` for kind.
- **Workloads (all in the cluster):** PostgreSQL ×1 (StatefulSet + PVC); MediaMTX ×1, never scaled; backend Deployment under the HPA; nginx frontend Deployment.
- **Entry point & load balancing:** nginx serves the built SPA over HTTPS with a self-signed certificate, proxies `/api` to the backend Service and `/live` to MediaMTX with the same rewrites the Vite proxy does (strip `/live`, prefix the `Location` header, drop `WWW-Authenticate`), and never proxies `/internal/**`. nginx must resolve the backend through the Service and not pin long-lived upstream connections to one pod, so new pods receive traffic. Upload size limit at nginx matches the VOD max file size.
- **Shared state:** login sessions already live in PostgreSQL (ADR-0003). 영상 파일 go on one shared PVC mounted by every backend pod at the storage root (single node, so all pods share it). The MediaMTX auth callback targets the backend Service with the shared secret.
- **Backend changes:**
  - Container image: multi-stage build producing a Java 21 runtime image.
  - Spring Boot Actuator with only health exposed; liveness and readiness probe groups reachable without login; nothing else under actuator reachable.
  - Graceful shutdown with a bounded timeout; pod termination grace period exceeds it.
  - Every response carries `X-Served-By` with the instance name (pod hostname).
  - Scheduled jobs (송출 감시, 후원 만료) run each round only on the instance that wins a PostgreSQL transaction-scoped advisory lock (one key per job); losers skip the round silently. No new dependency.
  - Flyway already serializes concurrent migrations with its own lock; no change.
- **Config & secrets:** a script builds a Kubernetes Secret from `.env` (MEDIAMTX_AUTH_SECRET, TOSS keys if present, LAN_IP), never committed. DB credentials for the in-cluster PostgreSQL are local-only defaults.
- **Scripts:** cluster up (create cluster, build & load images, apply manifests, wait for ready) and down; k6 load test run through the `grafana/k6` Docker image against the HTTPS entry (ramp-up, ~3 min hold, stop), mixing public reads (방송 list, VOD list, 댓글); verification script holding one logged-in session and printing per-tick: time, `/api/members/me` result, 방송 count, 댓글 count, `X-Served-By`, current backend pod count.
- **Untouched:** compose.yaml dev flow, Vite config, frontend code.

## Testing Decisions

- Good tests check external behaviour through the highest seam; no tests of private methods or Spring wiring details.
- **Backend HTTP API seam** (prior art: existing Testcontainers MockMvc/`ApiSession` tests): `X-Served-By` present on responses (public and authenticated, including errors); actuator liveness/readiness answer `200` without login; other actuator paths are not reachable.
- **Job exclusivity seam** (new, same Testcontainers DB): two concurrent runs of the same scheduled job → exactly one does the work; sequential runs both run; different jobs don't block each other. Covers both 송출 감시 and 후원 만료.
- **Cluster entry seam** (manual, scripts not CI): the load and verification scripts against `https://localhost:8443`; output captured into the README.
- No frontend tests change; the frontend code doesn't change.

## Out of Scope

- Cloud deployment, registries, Helm, Ingress controllers, TLS from a real CA.
- Request-count (RPS) based scaling, Prometheus/Grafana.
- Scaling PostgreSQL or MediaMTX; multi-node clusters; object storage for 영상 파일.
- Real-phone 송출 verification in cluster mode (ready-for-human, like live ticket 07).
- Rate limiting, autoscaling the frontend nginx.

## Further Notes

- ADR-0005 records the decisions. Docker Desktop must be running for anything cluster-side.
- The machine already has `kubectl` from Docker Desktop.
