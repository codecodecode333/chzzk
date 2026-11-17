# 치지직 (Chzzk)

회원가입·로그인하고, VOD(화면 표기 "다시보기")를 등록·시청하고, 휴대폰으로 라이브 방송을 송출·시청하는 MVP. 용어는 `GLOSSARY.md`, 설계 결정은 `docs/adr/` 참고.

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

1. Copy `.env.example` to `.env` next to `compose.yaml` and fill it in. Both `docker compose` and the backend
   (`bootRun` from `backend/`) read it; re-run `docker compose up -d` and restart the backend after changing it.
   - `LAN_IP`: this PC's LAN IP (`ipconfig`, the Wi-Fi/Ethernet adapter's IPv4 address, e.g. `192.168.0.23`).
     MediaMTX advertises it as its WebRTC address; without it only a browser on this PC can connect (it defaults to
     `127.0.0.1`).
   - `MEDIAMTX_AUTH_SECRET`: a long random value (see `.env.example`). MediaMTX sends it to the backend's auth
     callback; while it is unset the backend refuses every publish and watch (and logs a warning at startup).
2. Allow inbound connections in the Windows firewall (PowerShell as administrator). Only the HTTPS page and WebRTC
   media are reachable from the LAN: MediaMTX's signaling (8889) and control API (9997) listen on loopback only and
   phones reach signaling through the Vite proxy, so the 송출 키 never crosses the network unencrypted.
   ```powershell
   New-NetFirewallRule -DisplayName "chzzk dev" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 5173
   New-NetFirewallRule -DisplayName "chzzk dev media" -Direction Inbound -Action Allow -Protocol UDP -LocalPort 8189
   ```
3. Start everything (`docker compose up -d`, backend, `npm run dev`), open `https://<PC IP>:5173` on the phone and
   accept the certificate warning. `/broadcasts/new` is the 송출 screen; the "시청 링크" it shows is the 시청 screen.

MediaMTX asks the backend's internal callback (`POST /internal/mediamtx/auth?secret=<MEDIAMTX_AUTH_SECRET>`, not
proxied) on every WHIP/WHEP request: a call without the shared secret is refused (the backend listens on every
interface, so anyone on the LAN could otherwise ask it), publishing needs the 방송's current 송출 키 as `Authorization: Bearer`,
and only a 방송 중 방송 can be published or watched. `compose.yaml` puts the secret into MediaMTX's callback address
(`MTX_AUTHHTTPADDRESS`), since MediaMTX can't add headers. The 송출 키 only authenticates
that media connection: only the logged-in 소유자 gets one (from 방송 시작, or a fresh one from "이어서 송출", which
replaces the old key and cuts its publisher), the 송출 screen keeps it in memory only, and 방송 종료 uses the login
session. The backend reads MediaMTX's control API on `localhost:9997` (override with `MEDIAMTX_API_URL`).
Every 10s the publishing monitor asks that API which 방송 are receiving media; a 방송 중 방송 with no media for
over 60s (or that never sent any) is ended automatically. While MediaMTX can't be asked, only 방송 that never sent
any media are ended (for the others, silence can't be told from the outage).

### Real-phone checklist

Real WebRTC through MediaMTX isn't run in the automated tests, so check it by hand on the same Wi-Fi before
calling 라이브 done (ticket 07). Use two phones and a desktop browser; "list" is the 라이브 home (`/`).

**A. Android Chrome as 송출자 → iPhone Safari and a desktop browser as 시청자**
- [ ] On Android Chrome open `/broadcasts/new`: it asks to log in first. Log in as the 송출자 (회원 A), allow camera
      and microphone, enter a title, press 방송 시작: the 방송 중 badge and the 방송 경과 시간 timer appear.
- [ ] On iPhone Safari log in as another 회원 (B) and open the 시청 링크: it shows A's 닉네임 and no 송출 or 종료
      controls; the desktop browser, logged out, shows the same.
- [ ] Open the 시청 링크 on iPhone Safari and on the desktop: video plays muted with sub-second delay (wave a hand,
      or show a stopwatch on the 송출 phone and compare).
- [ ] Press "소리 켜기" on each 시청자: sound plays.
- [ ] Switch the camera (전면 ↔ 후면) on the 송출 phone while live: the 시청자 picture switches without reconnecting
      and the 방송 keeps its link and timer.
- [ ] The list shows the 방송 under 방송 중 with the 방송 중 badge and A's 닉네임.
- [ ] Press 방송 종료 and confirm: the 송출 phone shows 종료됨 and its camera turns off; every 시청자 switches to
      "종료된 방송입니다"; the list shows it under 종료된 방송 with the 종료 badge and both its start and end time.
- [ ] Second 회원: on the iPhone (logged in as B) open A's 시청 링크 while 방송 중 and again after 종료: neither
      shows 송출 ("이어서 송출"), 제목 수정 or 삭제 controls, while A's phone shows 제목 수정 (and 삭제 once 종료).

**B. iPhone Safari as 송출자 → Android Chrome as 시청자**
- [ ] The same flow as A with the roles swapped (log in on the iPhone as the 송출자): start, sub-second playback,
      "소리 켜기", camera switch while live, 방송 종료 → "종료된 방송입니다", and the list's 방송 중 / 종료 badges.

**C. Interruptions** (either phone as 송출자, the other as 시청자)
- [ ] Open the 시청 링크 before pressing 방송 시작 on a just-started 방송, or while the 송출자 reconnects: the 시청자
      shows "송출 준비 중…" and starts playing on its own once media flows.
- [ ] Reload the 송출 page while live and press "이어서 송출" within 60s: the same 방송 continues (same 시청 링크, same
      start time) and the 시청자 recover on their own.
- [ ] Resume on a second device: while the first phone is live, log in as the same 회원 on the other phone (or a
      desktop browser), open `/broadcasts/new` and press "이어서 송출": the 방송 continues from the second device; the
      first one shows "송출이 끊겼습니다", and its "다시 연결" says another device took over (its old 송출 키 is
      refused). End the 방송 from either device.
- [ ] Turn Wi-Fi off and on again on the 송출 phone: it shows "송출이 끊겼습니다"; "다시 연결" continues the same 방송.
- [ ] Kill the 송출 tab (close it, or lock the phone): within about 70s the 방송 shows 종료 in the list and
      "종료된 방송입니다" for the 시청자.

## 공개 범위 (access policy)

Who may do what, as the server enforces it (the pages only hide controls; the server stays the authority).
누구나 = anyone, logged in or not; 로그인 회원 = any logged-in 회원; 소유자 = the 회원 who registered the VOD or started
the 방송; 작성자 = the 회원 who wrote the 댓글.

Not logged in → `401`, logged in but not the 소유자/작성자 → `403`, a conflict with the current state → `409`, all as
Problem Details. Login is checked first, so a visitor gets `401` even for an unknown id; a 회원 gets `404` for an unknown
VOD, 방송 or 댓글. Writes (POST/PUT/PATCH/DELETE) also need the CSRF token (`403` without it); the frontend's HTTP
client sends it by itself.

| Action | Who | Otherwise |
| --- | --- | --- |
| 회원가입 (`POST /api/auth/signup`; logs the new 회원 in) | 누구나 | `409` naming the field for a taken email or 닉네임 |
| 로그인 (`POST /api/auth/login`) | 누구나 | `401` with one message for a wrong email or password |
| 로그아웃 (`POST /api/auth/logout`) | 누구나 (ends the session if there is one) | — |
| 내 정보 (`GET /api/me`) | 로그인 회원 | `401` |
| VOD 목록·상세 (`GET /api/vods`, `/api/vods/{id}`), 재생 (`GET /api/vods/{id}/stream`) | 누구나 | — |
| 방송 목록·상세 (`GET /api/broadcasts`, `/api/broadcasts/{id}`; `?ownerId=` narrows the 목록 to one 회원's 방송, still public data) | 누구나 | — |
| 방송 시청 (WHEP through MediaMTX) | 누구나, while 방송 중 | refused by the auth callback once 종료 |
| VOD 등록 (`POST /api/vods`) | 로그인 회원 (becomes its 소유자) | `401` |
| VOD 수정·삭제 (`PATCH`, `DELETE /api/vods/{id}`; 삭제 takes its 영상 파일 and 댓글) | 소유자 | `401` / `403` |
| 방송 시작 (`POST /api/broadcasts`) | 로그인 회원 (becomes its 소유자); one 방송 중 방송 per 회원 | `401`; `409` with `broadcastId` of the 방송 중 one |
| 방송 종료 (`POST /api/broadcasts/{id}/end`) | 소유자, by login session; ending twice is harmless | `401` / `403` |
| 이어서 송출 (`POST /api/broadcasts/{id}/publish-key`, a fresh 송출 키) | 소유자, while 방송 중 | `401` / `403`; `409` once 종료 |
| 송출 (WHIP through MediaMTX) | whoever holds the 방송's current 송출 키, while 방송 중 | refused by the auth callback |
| 방송 제목 수정 (`PATCH /api/broadcasts/{id}`) | 소유자, 방송 중 or 종료 | `401` / `403` |
| 방송 삭제 (`DELETE /api/broadcasts/{id}`, with its 댓글 and its FAILED 후원 orders) | 소유자, once 종료, and only without 후원 or orders that may still be paid | `401` / `403`; `409` while 방송 중; `409` "후원 내역이 있는 방송은 삭제할 수 없습니다" once it has a confirmed 후원; `409` "결제가 진행 중인 후원이 있어 지금은 삭제할 수 없습니다" while an order is PENDING or EXPIRED |
| 댓글 목록 (`GET /api/vods/{id}/comments`, `/api/broadcasts/{id}/comments`; `?before=` for older, `?after=` for newer) | 누구나 | `404` for an unknown VOD or 방송 |
| 댓글 쓰기 (`POST` to the same paths; on a 방송 중 or 종료 방송) | 로그인 회원 | `401`; `404` for an unknown VOD or 방송 |
| 댓글 수정·삭제 (`PATCH`, `DELETE /api/comments/{id}`) | 작성자 | `401` / `403` |
| 후원 내역·합계 (`GET /api/broadcasts/{id}/donations`, confirmed 후원 only, newest confirmed first; each entry's `seq` is its place in confirmation order and the cursor: `?before=` for older, `?after=` for newer; `total` counts confirmed 후원 only) | 누구나, 방송 중 or 종료 | `404` for an unknown 방송 |
| 후원 시작 (`POST /api/broadcasts/{id}/donations`, a PENDING 후원 order) | 로그인 회원 who is not the 소유자, while 방송 중 | `401`; `403` on your own 방송; `409` once 종료; `400` for the amount (1,000–1,000,000원 in 1,000원 steps) or a 메시지 over 100자; `404` for an unknown 방송 |
| 후원 확정 (`POST /api/donations/confirm`, from the success page; the server confirms with Toss) | that order's 후원자 | `401` / `403`; `404` for an unknown order; `400` with Toss's `code` when rejected, not paid (`ABORTED`, `EXPIRED`, `CANCELED`, ...) or the amount differs; `409` for a failed order; `503` when Toss can't be reached or the payment is still in progress there (the order stays open; retry) |
| 후원 실패 (`POST /api/donations/{orderId}/fail`, from the fail page) | that order's 후원자 | `401` / `403`; `404` for an unknown order |
| Toss payment webhook (`POST /internal/payments/webhook`, no session or CSRF) | anyone; the body is only a hint, re-checked by looking the payment up with Toss | always `200`, except `503` when Toss can't be reached |
| MediaMTX auth callback (`/internal/mediamtx/auth`) | MediaMTX with the shared secret | bare `401` |

시청, 목록 and 댓글 목록 need no login: a VOD or 방송 link shared with someone should just play, and reading the 댓글
next to it reveals nothing that isn't already public (only the 작성자's 닉네임, never an email). Login is asked only
where a write has to be tied to a 회원: who owns a VOD or 방송, who wrote a 댓글.

Login and 회원가입 also issue a fresh CSRF token (a new `XSRF-TOKEN` cookie), so a token known before login stops
working; the frontend client reads the cookie on every write, so it picks the new one up by itself.

While a 방송 is 방송 중, its 시청 page polls for new 댓글 every 5s (with `?after=`, page by page, so none is skipped).
Polling brings new 댓글 only: other people's edits and deletions show after a reload.

Only a CONFIRMED order is a 후원. Deleting a 종료 방송 removes its FAILED orders with it (final, never 후원). A 방송
with any confirmed 후원 stays, because payment records must not disappear, and so does one with a PENDING or EXPIRED
order, because Toss may still approve that payment (an EXPIRED order is confirmed if Toss later shows it paid): such a
방송 can't be deleted. The `donation` FK is `ON DELETE RESTRICT` as a backstop.

### Running with login

Nothing extra beyond the steps above: Flyway creates the 회원 and session tables on backend start, and sessions live
in the same PostgreSQL (`.env` is only needed for 라이브 on a phone). Sign up at `/signup`; there are no seeded 회원.

### Upgrading an existing database

Migration `V4__vod_owner.sql` deletes every VOD row registered before 회원 existed (nobody can own them), and
`V6__broadcast_owner.sql` deletes every 방송 started before 회원 existed. Neither touches 영상 파일, so
the deleted VODs' files stay on disk under `videos/` in the storage root (`backend/data/` or `VOD_STORAGE_ROOT`).
To reclaim the space:
- right after upgrading, before any new VOD is registered: delete that folder (e.g. `rm -rf backend/data/videos`);
- later: keep only the files still referenced by a VOD (`docker compose exec postgres psql -U chzzk -d chzzk -c
  "SELECT storage_key FROM vod"`; each key is a path relative to the storage root) and delete the rest.
방송 have no files to clean up.

## 후원 (Toss Payments test mode)

후원 payments run against Toss Payments in **test mode only** (ADR-0004): approvals are simulated and no money moves,
even if a real card number is entered. The backend confirms every payment with Toss itself; the browser only opens
Toss's 결제창 with the client key the order response hands it.

**Keys.** Real test-mode payments need **your own** Toss test keys. The public 결제창 test keys printed in Toss's docs
and sample repo (the defaults in `application.yml`) are now refused by Toss with `UNAUTHORIZED_KEY`, so with them the
결제창 or the confirm fails. To get working keys:
1. Sign up (free, email only) at the Toss Payments 개발자센터 (https://developers.tosspayments.com).
2. Under API 키, copy the **API 개별 연동** test pair: client key `test_ck_…` and secret key `test_sk_…` (not the
   `test_gck_…`/`test_gsk_…` 결제위젯 pair; the frontend uses the 결제창 SDK, and the server looks orders up by
   `orderId`, which a `gsk` key can't).
3. Put them in the repo-root `.env` (git-ignored; see `.env.example`) and restart `./gradlew bootRun`:
   ```sh
   TOSS_CLIENT_KEY=test_ck_...
   TOSS_SECRET_KEY=test_sk_...
   ```
   The secret key stays on the server; never commit it or put it in the frontend.

**Test mode is enforced.** The backend refuses to start unless both keys start with `test_`. Only to use other keys
on purpose, set `TOSS_ALLOW_NON_TEST_KEYS=true`; this project never handles real money.

None of this is needed for the tests: every backend test replaces Toss with the scriptable fake
`com.chzzk.donation.FakePaymentGateway` (`DonationTestConfig`), and frontend tests mock the API and the payments
module, so no test ever calls Toss.

**Webhooks.** A paid 후원 is confirmed even if the 후원자 never returns to the success page: Toss's
`PAYMENT_STATUS_CHANGED` webhook goes to `POST /internal/payments/webhook`. How it's protected: the event has no
signature, so the backend reads only `paymentKey`/`orderId` from it and **looks the payment up with Toss** (by
`paymentKey`, else by `orderId`); the order settled is the one Toss names, under the same rules as the success page
(`DONE`, our amount, our orderId), and amounts or statuses in the body are ignored. A forged webhook can therefore
only make the backend ask Toss about a payment. Every event is answered `200` (unknown orders and other events are
ignored), except `503` when Toss can't be reached for the lookup, so Toss retries. Repeated webhooks, and a webhook
racing the success page, count once. The endpoint sits under `/internal`, which the Vite dev server doesn't proxy:
Toss must reach the backend itself, over the public internet (it refuses localhost and LAN URLs), and webhooks are
registered per account, so only with your own keys:
1. Expose the backend (port 8080, not Vite's 5173) with a tunnel, e.g. `ngrok http 8080` (or
   `cloudflared tunnel --url http://localhost:8080`), and note the public `https://…` URL.
2. In the 개발자센터, 웹훅 → 웹훅 등록: `https://<tunnel>/internal/payments/webhook`, event `PAYMENT_STATUS_CHANGED`.
3. The tunnel URL changes on each restart (free ngrok), so update the registration when it does. Without a webhook
   everything still works through the success page.

The webhook stays unauthenticated by design (the lookup is the check), so what's left to abuse is cost: anyone can call
it, and each call can make the backend do up to two Toss lookups. It can't change an order, and the values it carries
are logged only at debug level and sanitized. In production, put rate limiting and an allow-list of Toss's webhook IP
addresses in front of `/internal/payments/webhook`; neither is implemented here.

**Expiry.** Orders still PENDING 30 minutes after they were created (Toss's 결제창 is valid that long) become EXPIRED;
a job checks every minute (`donation.expiry.interval`; `donation.expiry.enabled=false` switches it off). EXPIRED orders never count, but if a later confirm or webhook
shows Toss has the payment `DONE` for the order's amount, the order is still confirmed: the money was paid.

### Manual test-payment checklist

With your own test keys in `.env`, the stack running (Run locally), and two 회원 (two browsers, or one private window):
- [ ] 회원 A starts a 방송 (`/broadcasts/new`); 회원 B opens its 시청 page and sees "후원하기" (A, on their own 방송, does not).
- [ ] B picks 5,000원, adds a 메시지 and submits: Toss's test 결제창 opens for 5,000원 and the order name "후원 5,000원".
- [ ] B pays with any card in the test 결제창: it returns to `/donations/success`, which shows "확인 중…" then
      "후원 완료" with 5,000원. In the 개발자센터's 테스트 결제내역 the payment shows as 완료 (no money was taken).
- [ ] Refresh the success page: it shows the same "후원 완료" again, and the 테스트 결제내역 still has exactly one
      payment for that order (confirming again doesn't pay or count twice).
- [ ] B starts another 후원 and closes or cancels the 결제창: it lands on `/donations/fail` ("후원하지 못했습니다") with the reason, and no
      후원 is recorded.
- [ ] A ends the 방송 and tries 삭제 on its 시청 page: it stays, showing "후원 내역이 있는 방송은 삭제할 수 없습니다".

## Tests

```sh
cd backend && ./gradlew test         # HTTP API seam: full Spring context + Testcontainers PostgreSQL
cd frontend && npm test              # Page components: Vitest + Testing Library, network mocked with MSW
```

Conventions:
- Backend tests act as a visitor or a 회원 only through the real auth API, with `com.chzzk.support.ApiSession` (test
  sources): `ApiSession.visitor(http)` (CSRF token fetched), `ApiSession.member(http)` (a fresh unique 회원, signed up
  and logged in; `.member()` gives its `id`, `nickname`, `email`, `password`), `signUp(...)`, `logIn(...)`. Each
  instance keeps its own `SESSION`/`XSRF-TOKEN` cookies and sends `X-XSRF-TOKEN` on every request, so several 회원 can
  act in one test; use its `get/post/patch/delete` (JSON) or `exchange(path, method, body-or-HttpEntity, type)`.
  `withoutCsrfToken()` and `copy()` cover CSRF rejection and stale-cookie cases (`member.AuthApiTest`).
- Backend tests go through HTTP only (`backend/src/test/.../VodApiTest.java`, `BroadcastApiTest.java`); video storage is rooted in a temp dir. 방송 tests replace the clock (`MutableClock`) and the media server gateway (`FakeMediaServerGateway`) via `BroadcastTestConfig`.
- Frontend tests render routes via `AppRoutes` in a `MemoryRouter` and register MSW handlers per test (`src/test/server.ts`).
  By default everyone is a visitor (`/api/me` → 401) and `GET /api/auth/csrf` answers `CSRF_TOKEN`; call
  `loggedInAs({ id, nickname })` to be a 회원 (it returns `logIn`/`logOut` to switch mid-test). See
  `src/pages/authPages.test.tsx`.
- 회원 auth (ADR-0003): the member module `com.chzzk.member` owns signup, login, the email/password/닉네임 rules
  (`MemberRules`) and BCrypt. Features never touch Spring Security: inject `common.auth.CurrentMember` into the domain
  service and call `require()` (→ `MemberRef(id, nickname)`, or 401 for a visitor) or `find()`. Shared access errors
  live in `common.error`: throw `UnauthenticatedException` (401), `ForbiddenException` (403, not the 소유자/작성자) or
  `ConflictException` (409; `ConflictException.onField(field, msg)` names a form field, `.with(name, value)` adds a
  property such as the existing 방송's id); `ApiProblemHandler` turns them into Problem Details with Korean messages.
  `common.auth.SecurityConfig` holds the URL-level policy (today every URL is reachable and login/ownership are
  enforced in services), the cookie-to-header CSRF (`XSRF-TOKEN` cookie → `X-XSRF-TOKEN` header; MediaMTX's callback
  is exempt) and logout. Sessions live in PostgreSQL (Spring Session JDBC, `SESSION` cookie: HttpOnly, SameSite=Lax,
  7-day idle timeout).
- Frontend 회원 state: `useCurrentMember()` (`src/members/currentMemberContext.ts`) gives `{ member, loading, refresh,
  logOut }`; `CurrentMemberProvider` (rendered by `AppRoutes`) loads `/api/me` once. Guard a screen that needs login
  with `<RequireMember>` (`src/members/RequireMember.tsx`): a visitor goes to `/login?next=<here>`. Link to login with
  `withNext('/login', path)` and only follow `next` through `safeNext` (`src/members/next.ts`, in-app paths only). The
  shared `client.ts` adds the CSRF header to every write by itself; show per-field server errors with
  `apiFieldErrors(error)` and form fields with `forms/Field.tsx`.
- Backend packages: `com.chzzk` is the feature-neutral application root (`BackendApplication`). Each feature is a module beneath it (`com.chzzk.vod` with `domain/`, `storage/`, `web/`; `com.chzzk.broadcast` with `domain/`, `media/`, `web/`). Code shared by features lives in `com.chzzk.common` (e.g. `common.web`, `common.text.Title` for the shared title rule); features don't depend on each other. A feature that needs a 회원 (a VOD's or 방송's 소유자) maps its own read-only view of the `member` table (`vod.domain.VodOwner`, `broadcast.domain.BroadcastOwner`) instead of importing the member module; the API shape of any 회원 is `common.auth.MemberRef` (`{id, nickname}`).
- 방송 status rules, the 송출 키 (`PublishKey`), publish/read authorization and the 60s auto-end (`sweep`) live only in `broadcast.domain.BroadcastLifecycle`; every 방송 중 → 종료 is a conditional update, so 방송 종료 and the auto-end can't both win; `broadcast.PublishingMonitor` feeds it from the media server every 10s (API tests switch its schedule off with `broadcast.publishing-monitor.enabled=false` and call `check()` themselves). MediaMTX is reached only through `broadcast.media.MediaServerGateway` (`MediaMtxGateway`); `MediaPaths` is the one `broadcasts/{id}` mapping.
- Every API error is RFC 7807 Problem Details (Spring `ProblemDetail`: `type, title, status, detail, instance`, plus `errors: [{field, message}]` for field validation, built with `common.web.Problems.fieldError`). `common.web.ApiProblemHandler` covers Spring's own exceptions and the 500 catch-all; each feature maps its domain exceptions in its own `@RestControllerAdvice` ordered `ApiProblemHandler.FEATURE_ORDER` (e.g. `vod.web.VodProblemHandler`). Throw `ResponseStatusException` or a mapped domain exception; don't hand-roll error bodies.
  The one exception is MediaMTX's auth callback (`broadcast.web.MediaServerAuthController`): MediaMTX's contract wants
  an empty 2xx to allow and any other status to deny, so it answers a bare `204`/`401` with no body.
- 댓글 (`com.chzzk.comment`) attach to a `CommentTarget` (`vod(id)` or `broadcast(id)`): `CommentService.list/write/edit/delete`
  checks login and authorship itself; the `comment` table has `vod_id`/`broadcast_id` (exactly one set, both
  `ON DELETE CASCADE`). It reads target existence and the 작성자's 닉네임 straight from the shared tables (native
  exists queries on the `vod` and `broadcast` tables, a read-only view of `member`), so it depends on no other feature
  module; by design that couples it to those table names instead, so renaming them means updating
  `comment.domain.CommentRepository`. Paging is a cursor, newest first by id: `?before=<nextBefore>&size=` for older
  댓글, `?after=<newest id read>&size=` (follow `nextAfter` until `null`) for newer ones when polling. On the
  frontend, `<CommentSection target={{ kind: 'vod' | 'broadcast', id }} />` (`src/comments/`) is the one 댓글 UI, over
  `src/api/comments.ts`.
- Registration form rules live in `frontend/src/vods/validateVodForm.ts` (pure, mirrors the server), separate from how the form submits.
- All frontend data access lives in `frontend/src/api/` (`vods.ts`, `broadcasts.ts`, `auth.ts`, shared `client.ts`); extend it rather than calling the network from pages.
- Only `frontend/src/media/media.ts` touches the camera, `RTCPeerConnection` and WHIP/WHEP; pages call it and page tests `vi.mock` it (`src/pages/broadcastPages.test.tsx`).
