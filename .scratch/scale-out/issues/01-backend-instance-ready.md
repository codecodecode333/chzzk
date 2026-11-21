# 01: 백엔드를 여러 인스턴스로 돌릴 준비 (이미지·헬스·종료·응답 인스턴스 표시)

**What to build:** The backend can be built into a container image and behaves well as one of many instances. It exposes liveness and readiness health checks that need no login, finishes in-flight requests before shutting down, and tags every response with which instance served it (`X-Served-By`). No other management endpoint is reachable. See spec (`.scratch/scale-out/spec.md`) and ADR-0005.

**Blocked by:** None - can start immediately

**Status:** ready-for-agent

- [ ] A multi-stage container build produces a Java 21 runtime image of the backend; the build works from a clean checkout and is documented in one command
- [ ] Actuator health only: liveness and readiness groups answer `200` without login; other actuator paths are not reachable (`404`/`401`)
- [ ] Graceful shutdown enabled with a bounded timeout
- [ ] Every response (public, authenticated, error) carries `X-Served-By` = the instance's hostname
- [ ] The VOD storage root, DB URL, MediaMTX API URL and auth secret, and Toss keys stay configurable by environment variable (no change of defaults for dev mode)
- [ ] HTTP API seam tests cover the header and the health/actuator exposure
- [ ] The dev flow (bootRun + compose + Vite) is unchanged
