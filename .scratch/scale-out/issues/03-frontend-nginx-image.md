# 03: 프론트엔드 nginx 이미지 (HTTPS 진입점)

**What to build:** The frontend can be built into an nginx image that serves the built screens over HTTPS (self-signed certificate generated at build or start) and is the single entry point in cluster mode. It proxies `/api` to the backend Service and `/live` to MediaMTX exactly like the Vite dev proxy (strip `/live`, keep `Location` under `/live`, drop `WWW-Authenticate`), never proxies `/internal/**`, falls back to `index.html` for client routes, and allows uploads up to the VOD max file size. Upstream hosts are configurable by environment variable. See spec and ADR-0005.

**Blocked by:** None - can start immediately

**Status:** ready-for-agent

- [ ] A multi-stage container build (node build → nginx) of the frontend
- [ ] HTTPS on one port with a self-signed certificate; client-route fallback to `index.html`
- [ ] `/api` → backend upstream without pinning long-lived connections to one pod (new pods get traffic); `/live` rewrites as the Vite proxy; `/internal/**` not proxied (`404`)
- [ ] Upload body limit ≥ the VOD max file size; long upload/playback requests not cut by proxy timeouts
- [ ] Smoke-checked with `docker run` against a locally running backend (documented in the ticket notes)
- [ ] Frontend source and Vite config unchanged
