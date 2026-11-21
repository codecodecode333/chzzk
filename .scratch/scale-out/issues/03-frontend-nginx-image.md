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

## Notes

Smoke check (2026-11-22): `docker build -t so03-frontend frontend`, run on a user network with Python stub upstreams
(two `backend` aliases on :8080, one `mediamtx` on :8889; the real backend was not used), published on 18443.
- `/`, `/vods/3` → 200 SPA shell; `/assets/<hashed>.js` → 200; `/assets/nope.js` → 404; `/healthz` → 200.
- `/internal`, `/internal/mediamtx/auth`, `/api/../internal/x` (raw and `%2e%2e`) → 404; `/api/a/../vods` raw → 400.
- `/api/vods?page=1` reaches the backend stub with path, query and original Host intact; a 200 MB multipart POST passes.
- `/live/cam/whip` → MediaMTX stub sees `/cam/whip` with `Host: mediamtx:8889`; `Location: /live/cam/whip/session-1`;
  `WWW-Authenticate` dropped on 201 and 401.
- Spreading: 20 requests split 6/14 over two stubs; a third stub added later got 9 of the next 30 within ~12 s;
  no upstream connections left ESTABLISHED after requests (no keepalive pinning).
