# 02: 운영용 Compose·Caddy 구성과 배포 스크립트

**What to build:** A production stack that runs the whole service on one host from one env file: Caddy (automatic Let's Encrypt for the configured host, HTTP→HTTPS), backend (`prod` profile), frontend static files, PostgreSQL and MediaMTX, all with restart policies and named volumes, built from the repo on the host (works on ARM64). Caddy routes `/api` (large uploads, long timeouts), `/live` (same rewrites as the Vite/nginx proxies), `/healthz` → backend overall health, refuses `/internal`, and serves the SPA with fallback. MediaMTX listens for WebRTC on UDP and TCP 8189 with the host's public IP as additional host and calls the backend auth callback with the shared secret. Also: a deploy script run from the operator's PC (SSH, update checkout, compose up --build, wait for `/healthz`) and a one-time VM bootstrap script (Docker install, iptables for 80/443/8189 udp+tcp, `.env` template with mode 600). See spec and ADR-0006.

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] Production compose + Caddyfile driven by env (public host, optional ACME email, public IP, secrets); nothing secret committed; prod `.env` example
- [ ] Verified on this PC with a local host name and Caddy's internal CA: SPA, `/api`, `/live` rewrite, `/healthz`, `/internal` refused, VOD upload/playback, login cookie Secure
- [ ] Deploy script and VM bootstrap script (Ubuntu 24.04 aarch64) written and reviewed; README section on deploying
- [ ] Local dev compose and kind cluster mode unchanged
