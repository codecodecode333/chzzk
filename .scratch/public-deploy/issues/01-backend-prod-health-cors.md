# 01: 백엔드 운영 설정, 공개 상태 확인, 데스크톱 앱 CORS

**What to build:** The backend can run behind the public HTTPS proxy with a `prod` profile: the login session cookie is `Secure`, forwarded headers from the proxy are trusted, the public base URL (one env value, e.g. `https://live.codecodecode333.dev`) drives Toss success/fail redirects, and secrets come only from env (missing required secret → refuse to start; Toss keys keep ADR-0004's test-key rule). A public overall health check answers only UP/DOWN and includes the database. The 데스크톱 앱's own origin may `GET` the public 방송 reads cross-origin; no other origin, endpoint or method gets CORS. Dev defaults stay unchanged. See `.scratch/public-deploy/spec.md`, ADR-0006.

**Blocked by:** None - can start immediately

**Status:** ready-for-agent

- [ ] `prod` profile: Secure session cookie, forwarded-header trust, public base URL env used for Toss redirects, required secrets from env with fail-fast
- [ ] Overall health (incl. DB) reachable without login, status only, no details; existing liveness/readiness unchanged
- [ ] CORS: the 데스크톱 앱 origin (configurable, default the app's custom protocol origin) may preflight and `GET` public 방송 list/detail; other origins, other endpoints and non-GET methods get no CORS allowance; no credentials
- [ ] HTTP API seam tests cover all of the above (prod-profile behaviour included)
- [ ] README documents the prod env variables
