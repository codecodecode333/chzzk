# 02: 휴대폰으로 방송 시작 → 링크로 시청 (walking skeleton)

**What to build:** A 송출자 opens the 송출 screen on a phone (same Wi-Fi, HTTPS). They see a camera preview, enter a title and press "방송 시작". A new 방송 is created as 방송 중, a 송출 키 is issued once, and the phone publishes over WHIP through MediaMTX. A 시청자 opening `/broadcasts/:id` sees the 방송's title and watches it live over WHEP with sub-second latency. Playback autoplays muted with a "소리 켜기" overlay.

This ticket lays the live foundation every later ticket builds on:
- MediaMTX in Compose, wired to the backend's auth callback
- Vite on HTTPS (LAN) proxying `/api` and `/live`
- the `broadcast` schema
- the broadcast lifecycle module and the media server gateway interface (with a fake for tests)
- the frontend media module

Follow the spec (`.scratch/live-broadcast/spec.md`), ADR-0002, and the GLOSSARY 라이브 terms.

**Blocked by:** 01 (백엔드 패키지 재구성)

**Status:** resolved

- [x] Compose runs PostgreSQL and MediaMTX; MediaMTX config is checked in, uses the backend's internal auth callback, and takes the host LAN IP from an env var
- [x] Vite dev server serves HTTPS (self-signed) on all interfaces and proxies `/api` and `/live` (WHIP/WHEP) so the page never calls MediaMTX cross-origin
- [x] A Flyway migration creates the `broadcast` table as specified (status, hash of the 송출 키, start/end/last-publishing times, and the indexes and check constraint)
- [x] `POST /api/broadcasts` `{title}` → `201` + `Location`, with `{id, title, status: LIVE, startedAt, publishKey, whipUrl}`; the key is 32 random bytes base64url, returned only here, and stored only as its SHA-256 hash
- [x] `GET /api/broadcasts/{id}` → `{id, title, status, startedAt, endedAt, whepUrl}` (`whepUrl` only while LIVE); `404` Problem Details for an unknown id
- [x] The internal MediaMTX auth endpoint allows publish only for a LIVE 방송 with the matching key (Bearer on WHIP), and read only for a LIVE 방송; it is not reachable through the public proxy
- [x] The frontend media module wraps `getUserMedia`, `RTCPeerConnection`, and WHIP/WHEP signaling; pages depend only on it
- [x] The 송출 screen `/broadcasts/new` shows a front-camera preview, a title input, and 방송 시작 → it calls the start API, publishes, and shows a 방송 중 badge with an elapsed timer
- [x] The 시청 screen `/broadcasts/:id` connects over WHEP for a LIVE 방송, autoplays muted, shows "소리 켜기", and shows a not-found view for an unknown id
- [x] The README documents the LAN setup: the LAN IP env var, Windows firewall ports (5173, 8889, 8189/udp), and opening `https://<PC IP>:5173` on the phone and accepting the certificate
- [x] HTTP API seam tests (gateway faked): start (`201`, key present once), detail with and without `whepUrl`, detail `404`, and auth-callback allow/deny for publish (right key, wrong key, unknown id) and read
- [x] Frontend seam tests (media module mocked): start flow calls the API and publishes, and the viewer for a LIVE 방송 connects and shows "소리 켜기"
- [x] Manually verified at least once: PC browser publishes, a second browser tab watches
