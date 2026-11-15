# 치지직 (Chzzk)

로그인 없이 VOD(화면 표기 "다시보기")를 등록·시청하고, 휴대폰으로 라이브 방송을 송출·시청하는 MVP. 용어는 `GLOSSARY.md`, 설계 결정은 `docs/adr/` 참고.

## Layout

| Path | What |
| --- | --- |
| `backend/` | Spring Boot 3 (Java 21, Gradle Kotlin DSL), Flyway, PostgreSQL |
| `frontend/` | React + Vite + TypeScript + Tailwind CSS |
| `compose.yaml` | Local PostgreSQL and MediaMTX (backend and frontend run natively) |
| `mediamtx/mediamtx.yml` | MediaMTX config: WebRTC only, auth via the backend's internal callback (ADR-0002) |

## Prerequisites

- Docker (for PostgreSQL and for the backend tests, which use Testcontainers)
- Any JDK 17+ to launch Gradle; the Gradle toolchain downloads JDK 21 automatically
- Node.js 24+

## Run locally

```sh
docker compose up -d                 # PostgreSQL on localhost:5432 (db/user/password: chzzk) + MediaMTX (see 라이브 below)

cd backend && ./gradlew bootRun      # http://localhost:8080
# Uploaded 영상 파일 are stored under backend/data/ (git-ignored).
# Override with VOD_STORAGE_ROOT, VOD_MAX_FILE_SIZE (default 1GB), DB_URL, DB_USERNAME, DB_PASSWORD.

cd frontend && npm install && npm run dev   # https://localhost:5173 (self-signed), proxies /api to :8080 and /live to MediaMTX
```

영상 파일 size limit: set `VOD_MAX_FILE_SIZE` (backend, e.g. `512MB`; the multipart request limit is derived from it)
and `VITE_VOD_MAX_FILE_SIZE` (frontend, same format, default `1GB`, read at `npm run dev`/`npm run build` time) to the
same value. The form checks the size first; the server stays authoritative and answers `413` with the configured limit.

## 라이브 on a phone (same Wi-Fi)

The phone publishes and watches with WebRTC through MediaMTX (ADR-0002). Camera access needs HTTPS, so Vite serves a
self-signed certificate on all interfaces and proxies `/api` (backend) and `/live` (WHIP/WHEP signaling to MediaMTX);
media flows directly between the device and MediaMTX over UDP 8189.

1. Find this PC's LAN IP (`ipconfig`, the Wi-Fi/Ethernet adapter's IPv4 address, e.g. `192.168.0.23`) and put it in a
   `.env` next to `compose.yaml` (see `.env.example`): `LAN_IP=192.168.0.23`. MediaMTX advertises it as its WebRTC
   address; without it only a browser on this PC can connect (it defaults to `127.0.0.1`). Re-run
   `docker compose up -d` after changing it.
2. Allow inbound connections in the Windows firewall (PowerShell as administrator):
   ```powershell
   New-NetFirewallRule -DisplayName "chzzk dev" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 5173,8889
   New-NetFirewallRule -DisplayName "chzzk dev media" -Direction Inbound -Action Allow -Protocol UDP -LocalPort 8189
   ```
3. Start everything (`docker compose up -d`, backend, `npm run dev`), open `https://<PC IP>:5173` on the phone and
   accept the certificate warning. `/broadcasts/new` is the 송출 screen; the "시청 링크" it shows is the 시청 screen.

MediaMTX asks the backend's internal callback (`POST /internal/mediamtx/auth`, not proxied) on every WHIP/WHEP
request: publishing needs the 방송's 송출 키 as `Authorization: Bearer`, and only a 방송 중 방송 can be published or
watched. The backend reads MediaMTX's control API on `localhost:9997` (override with `MEDIAMTX_API_URL`).

## Tests

```sh
cd backend && ./gradlew test         # HTTP API seam: full Spring context + Testcontainers PostgreSQL
cd frontend && npm test              # Page components: Vitest + Testing Library, network mocked with MSW
```

Conventions:
- Backend tests go through HTTP only (`backend/src/test/.../VodApiTest.java`, `BroadcastApiTest.java`); video storage is rooted in a temp dir. 방송 tests replace the clock (`MutableClock`) and the media server gateway (`FakeMediaServerGateway`) via `BroadcastTestConfig`.
- Frontend tests render routes via `AppRoutes` in a `MemoryRouter` and register MSW handlers per test (`src/test/server.ts`).
- Backend packages: `com.chzzk` is the feature-neutral application root (`BackendApplication`). Each feature is a module beneath it (`com.chzzk.vod` with `domain/`, `storage/`, `web/`; `com.chzzk.broadcast` with `domain/`, `media/`, `web/`). Code shared by features lives in `com.chzzk.common` (e.g. `common.web`, `common.text` for the shared title trimming); features don't depend on each other.
- 방송 status rules, the 송출 키 and publish/read authorization live only in `broadcast.domain.BroadcastLifecycle`. MediaMTX is reached only through `broadcast.media.MediaServerGateway` (`MediaMtxGateway`); `MediaPaths` is the one `broadcasts/{id}` mapping.
- Every API error is RFC 7807 Problem Details (Spring `ProblemDetail`: `type, title, status, detail, instance`, plus `errors: [{field, message}]` for field validation, built with `common.web.Problems.fieldError`). `common.web.ApiProblemHandler` covers Spring's own exceptions and the 500 catch-all; each feature maps its domain exceptions in its own `@RestControllerAdvice` ordered `ApiProblemHandler.FEATURE_ORDER` (e.g. `vod.web.VodProblemHandler`). Throw `ResponseStatusException` or a mapped domain exception; don't hand-roll error bodies.
- Registration form rules live in `frontend/src/vods/validateVodForm.ts` (pure, mirrors the server), separate from how the form submits.
- All frontend data access lives in `frontend/src/api/` (`vods.ts`, `broadcasts.ts`, shared `client.ts`); extend it rather than calling the network from pages.
- Only `frontend/src/media/media.ts` touches the camera, `RTCPeerConnection` and WHIP/WHEP; pages call it and page tests `vi.mock` it (`src/pages/broadcastPages.test.tsx`).
