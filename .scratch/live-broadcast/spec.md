# 라이브 방송: 휴대폰으로 송출하고 목록에서 골라 시청

Status: ready-for-agent

## Problem Statement

치지직에는 이제 녹화된 VOD를 올려 두고 보는 경로만 있다. 방송 플랫폼의 핵심인 "지금 이 순간"을 보여 줄 방법이 없다. 송출자는 별도 장비나 앱 없이 들고 있는 휴대폰의 카메라·마이크로 바로 방송을 시작하고 끝내고 싶다. 시청자는 지금 방송 중인 방송을 목록에서 찾아 바로 보고 싶고, 어떤 방송이 이미 끝났는지도 한눈에 알고 싶다. 이번 단계에서도 로그인은 없다.

## Solution

송출자는 휴대폰 브라우저로 방송 시작 화면을 연다. 카메라 미리보기를 확인하고 제목을 넣은 뒤 "방송 시작"을 누르면 새 **방송**이 **방송 중** 상태로 생기고 송출이 시작된다. "방송 종료"를 누르면 방송이 **종료**된다. 송출이 끊긴 채 60초가 지나도 자동으로 종료된다.

방송을 시작할 때 서버는 **송출 키**를 한 번만 발급한다. 그 키를 가진 브라우저만 송출하거나 종료할 수 있다.

홈은 **방송 목록**이다. 방송 중인 방송이 위에, 종료된 방송이 아래에 상태 배지와 함께 보인다. 시청자는 방송 중인 방송을 눌러 1초 미만 지연으로 시청한다. 방송이 끝나면 화면이 "종료된 방송입니다"로 바뀐다. 기존 다시보기 목록은 `/vods`로 옮긴다.

## User Stories

1. As a 시청자, I want the home page to be a 방송 목록, so that live content is the first thing I see.
2. As a 시청자, I want 방송 중 broadcasts listed above 종료 broadcasts, so that I can find something live right away.
3. As a 시청자, I want each broadcast card to show a clear 방송 중 or 종료 badge, so that I know whether I can watch it now.
4. As a 시청자, I want 방송 중 broadcasts ordered by most recently started, so that new broadcasts are easy to spot.
5. As a 시청자, I want 종료 broadcasts ordered by most recently ended, so that I can see what just finished.
6. As a 시청자, I want each card to show the title and the start time (and the end time for ended ones), so that I can tell broadcasts apart.
7. As a 시청자, I want the list to refresh on its own, so that newly started broadcasts and newly ended ones show up without reloading.
8. As a 시청자, I want ended broadcasts paged, so that the list stays fast as history accumulates.
9. As a 시청자, I want a friendly empty state when nobody is broadcasting, so that an empty list doesn't look broken.
10. As a 시청자, I want to tap a 방송 중 broadcast and start watching immediately, so that I don't miss the moment.
11. As a 시청자, I want sub-second latency, so that what I see is truly live.
12. As a 시청자, I want playback to start automatically (muted), so that I see the broadcast without extra taps.
13. As a 시청자, I want a "소리 켜기" button, so that I can turn the sound on with one tap.
14. As a 시청자, I want to see "송출 준비 중…" while the broadcast exists but media isn't flowing yet, so that I wait instead of thinking it's broken.
15. As a 시청자, I want the player to reconnect on its own while the broadcast is still 방송 중, so that a brief hiccup doesn't end my viewing.
16. As a 시청자, I want the screen to switch to "종료된 방송입니다" when the broadcast ends, so that I know it's over rather than frozen.
17. As a 시청자, I want opening an ended broadcast to show its title, times and "종료된 방송입니다" with no player, so that the link still makes sense afterwards.
18. As a 시청자, I want a not-found view for broadcast links that don't exist, so that I know the link is wrong.
19. As a 시청자, I want the broadcast I'm watching to play on both Android Chrome and iPhone Safari, so that my device doesn't matter.
20. As a 시청자, I want 다시보기 still reachable from the navigation, so that VODs aren't lost when the home page changes.
21. As a 송출자, I want to start broadcasting from my phone's browser without installing an app, so that I can go live instantly.
22. As a 송출자, I want to see my camera preview before going live, so that I can frame myself first.
23. As a 송출자, I want the front camera by default and a button to switch to the back camera, so that I can show either myself or what's in front of me.
24. As a 송출자, I want to enter a title before starting, so that viewers know what the broadcast is.
25. As a 송출자, I want to be told if the title is empty or over 100 characters, so that I can fix it before going live.
26. As a 송출자, I want a clear message if camera or microphone permission is denied, so that I know how to fix it.
27. As a 송출자, I want a clear message if my browser can't broadcast (e.g. not HTTPS or unsupported), so that I'm not left guessing.
28. As a 송출자, I want "방송 시작" to put me live, with a 방송 중 badge and an elapsed timer, so that I know I'm broadcasting.
29. As a 송출자, I want a "방송 종료" button that asks for confirmation, so that I don't end the broadcast by accident.
30. As a 송출자, I want to see a "종료됨" screen after ending, so that I know the broadcast is over and the camera is released.
31. As a 송출자, I want to be warned before leaving the page while live, so that I don't cut the broadcast by mistake.
32. As a 송출자, I want to resume the same broadcast if I reload or come back within 60 seconds, so that a slip doesn't split my broadcast in two.
33. As a 송출자, I want to start a new broadcast after one has ended, so that I can go live again later.
34. As a 송출자, I want only my browser to be able to end or publish to my broadcast, so that nobody else can hijack or cut it.
35. As a 송출자, I want 720p 30fps video when my phone and network allow, so that viewers get decent quality.
36. As a 운영자, I want broadcasts whose publisher vanished to end automatically after 60 seconds, so that the list never shows ghost "방송 중" entries.
37. As a 운영자, I want a broadcast that never actually started sending media to end automatically, so that abandoned starts don't linger.
38. As a 운영자, I want the 송출 키 stored only as a hash, so that a database leak doesn't let anyone hijack broadcasts.
39. As a 운영자, I want the media server to refuse publishing without a valid key and refuse viewing ended broadcasts, so that access rules hold even when someone bypasses our UI.
40. As a 운영자, I want ending a broadcast to actually cut the publisher's media connection, so that "종료" means nothing is being relayed.
41. As a 운영자, I want broadcast state to survive a backend restart, so that a deploy doesn't lose or corrupt live broadcasts.
42. As a 개발자, I want to test on a real phone over the same Wi-Fi with one documented setup, so that camera access (HTTPS) and WebRTC work locally.

## Implementation Decisions

**Domain vocabulary** (see `GLOSSARY.md`; the 라이브 section is new):
- A **방송** is one 송출 session.
- Its **방송 상태** is 방송 중 (`LIVE`) or 종료 (`ENDED`). Moving from 종료 back to 방송 중 is impossible.
- A **송출자** is defined solely by holding the **송출 키**.
- Nothing is recorded, so an ended 방송 cannot be played back.
- VOD and 방송 are distinct concepts and must not share entities or tables.

**Architecture** (see ADR-0002):
- The phone browser publishes WebRTC via WHIP. MediaMTX relays it, and 시청자 receive it via WHEP.
- There is no transcoding: H.264 or VP8 video and Opus audio pass through unchanged.
- Our backend owns broadcast state and the 송출 키. MediaMTX owns media.
- MediaMTX runs as a Docker Compose service next to PostgreSQL.

**Prefactor**: the backend root package is currently VOD-named. Restructure it so VOD and 방송 are sibling feature modules under a neutral application root, with the shared Problem Details handling living outside either feature. Do this first, behaviour-neutral.

**Modules (backend)**
- **Broadcast lifecycle** (deep module): `start(title) → (Broadcast, publishKey)`, `get(id)`, `list(status, page)`, `end(id, publishKey)`, `authorizePublish(id, publishKey)`, `authorizeRead(id)`, `sweep(now)`.
  - It owns the status transitions, key generation and hashing, and the 60-second auto-end rule.
  - It depends on an injectable clock.
- **Media server gateway** (interface, MediaMTX implementation):
  - `listPublishingPaths() → set of broadcast ids currently receiving media`;
  - `kickPublisher(broadcastId)`.
  - It is faked in tests.
- **Publishing monitor** (scheduled every 10s): asks the gateway which broadcasts are publishing, records their last-publishing time, then calls `sweep(now)`.
- **HTTP layer**: the public broadcast API plus the internal MediaMTX auth callback, sharing the existing Problem Details shape.

**Lifecycle rules**
- **Start:** "방송 시작" creates the 방송 as `LIVE` with `started_at = now`.
- **Auto-end:** a `LIVE` 방송 ends automatically when no media has arrived for 60 consecutive seconds. That is counted from `last_publishing_at`, or from `started_at` if it never published. `ended_at` is set to when publishing stopped (the last-publishing time, or the start time).
- **Explicit end:** sets `ENDED` with `ended_at = now`, then asks the gateway to kick the publisher.
- **Ending twice:** ending an already-ended 방송 is a successful no-op.
- **Resume:** re-publishing within the 60s window with the same key continues the same 방송.

**송출 키**: 32 random bytes encoded as base64url, returned only once in the start response. Only its SHA-256 hash is stored, and comparison is constant-time.

**MediaMTX integration**
- **Path:** each 방송 maps to the path `broadcasts/{id}`.
- **Auth callback:** MediaMTX calls the backend's internal auth endpoint (HTTP) for every publish and read.
  - Publish is allowed only for a `LIVE` 방송 with a matching key; the key arrives as `Authorization: Bearer` on the WHIP request.
  - Read is allowed only for a `LIVE` 방송.
  - The internal endpoint is not exposed through the public proxy.
- **Polling:** the backend polls MediaMTX's control API path list for liveness, instead of relying on `runOnReady/NotReady` hooks, because a lost hook would leave a 방송 stuck `LIVE`.
- **Signaling:** WHIP and WHEP go through the frontend's own origin under `/live/...`, proxied to MediaMTX. This avoids mixed-content and CORS problems on the HTTPS page.
- **Media:** WebRTC media flows directly between the device and MediaMTX over UDP 8189. MediaMTX is told the host's LAN IP via configuration (`webrtcAdditionalHosts`).

**API contract** (errors are RFC 7807 Problem Details, as today)
- `POST /api/broadcasts` `{title}` → `201` + `Location`, body `{id, title, status, startedAt, publishKey, whipUrl}`.
  - The title follows the VOD title rule: trimmed with the same whitespace definition, 1–100 chars.
  - Errors: `400`.
- `GET /api/broadcasts?status=LIVE|ENDED&page=&size=` → the same page wrapper as the VOD list.
  - `LIVE` is ordered `startedAt DESC`, `ENDED` is ordered `endedAt DESC`.
  - The size default and cap match the VOD list; a missing or invalid status → `400`.
- `GET /api/broadcasts/{id}` → `{id, title, status, startedAt, endedAt, whepUrl}`.
  - `whepUrl` is present only while `LIVE`.
  - An unknown id → `404`.
- `POST /api/broadcasts/{id}/end` with `Authorization: Bearer {publishKey}` → `204`.
  - A missing or wrong key → `401`; an unknown id → `404`.
  - An already-ended 방송 → `204`.
- Internal: the MediaMTX auth callback → `2xx` allow or `401` deny. It is reachable only by MediaMTX.

**Schema** (a new Flyway migration): a `broadcast` table.

| Column | Type |
|---|---|
| `id` | BIGINT identity |
| `title` | VARCHAR(100) NOT NULL |
| `status` | VARCHAR NOT NULL (`LIVE`/`ENDED`) |
| `publish_key_hash` | VARCHAR NOT NULL |
| `started_at` | TIMESTAMPTZ NOT NULL |
| `ended_at` | TIMESTAMPTZ NULL |
| `last_publishing_at` | TIMESTAMPTZ NULL |

- Index on `(status, started_at DESC)` and on `(status, ended_at DESC)`.
- A check constraint ties `ended_at` to `ENDED`.

**Frontend**
- **Routes**:
  - `/` is now the 방송 목록: a 방송 중 section on top and a paged 종료 section below, with an empty state.
  - The 다시보기 list moves to `/vods`. VOD detail and registration keep their routes.
  - The navigation shows "라이브" and "다시보기".
  - `/broadcasts/new` is the 송출 screen and `/broadcasts/:id` is the 시청 screen.
- **Media module**: a thin module wraps `getUserMedia`, `RTCPeerConnection`, WHIP/WHEP signaling, codec preference and camera switching. Pages depend only on it, which is what makes them testable.
- **송출 screen** (portrait mobile):
  1. Request camera and mic, then show a front-camera preview. Handle the "permission denied" and "no secure context / unsupported" cases with messages.
  2. Enter a title, then press 방송 시작. This calls the start API, stores `{id, publishKey}` in `sessionStorage`, publishes via WHIP preferring H.264 (falling back to the browser default), and targets 720p30.
  3. While live: a 방송 중 badge, an elapsed timer, a front/back camera switch, and 방송 종료 with confirmation. Ending calls the end API, stops the tracks and shows "종료됨".
  4. `beforeunload` warns while live.
  5. On load, if `sessionStorage` holds a 방송 that is still `LIVE`, offer "이어서 송출" to republish to the same 방송.
- **시청 screen**:
  - If `LIVE`: connect via WHEP and autoplay muted with a "소리 켜기" overlay. While WHEP fails and the status is still `LIVE`, show "송출 준비 중…" and retry every 3s.
  - Poll the status every 5s, and immediately when the connection drops.
  - On `ENDED`, stop and show "종료된 방송입니다" with the title and times. An unknown id → not-found view.
- **List**: polls every 10s.
- **No push channel** (SSE/WebSocket) in this phase; one is expected with chat in the next phase.

**Dev environment**
- Compose adds `mediamtx` (8889/tcp for WHIP/WHEP, 8189/udp for media) with its config checked in.
- Vite serves HTTPS with a self-signed certificate on `0.0.0.0` and proxies `/api` and `/live`.
- The README documents the setup:
  - setting the LAN IP;
  - opening the Windows firewall for 5173, 8889 and 8189/udp;
  - opening `https://<PC IP>:5173` on the phone and accepting the certificate warning.

## Testing Decisions

- Good tests exercise external behaviour only: HTTP in and out on the backend, rendered UI and user interaction on the frontend. They never inspect internal classes, tables or MediaMTX config. The existing VOD tests are the model.
- **Seam 1: backend HTTP API** (prior art: the existing VOD API test). Full Spring context, Testcontainers PostgreSQL, with the **media server gateway faked** and an **injectable clock** to advance time. Cases:
  - Start: `201` with a key that's returned only once; a title validation `400`.
  - List: by status, with ordering and paging; detail with `whepUrl` only when `LIVE`; detail `404`.
  - End: the right key gives `204` and status `ENDED`, and the fake records a kick; a wrong or missing key gives `401`; ending twice gives `204`; an unknown id gives `404`.
  - Auth callback, publish: allowed with the right key on `LIVE`; denied with a wrong key, on `ENDED`, or for an unknown id.
  - Auth callback, read: allowed on `LIVE`, denied on `ENDED`.
  - Auto-end: a 방송 the fake reports as publishing stays `LIVE` as the clock advances. Once it stops being reported, it becomes `ENDED` after more than 60s, with `ended_at` equal to the last-publishing time. A never-published 방송 ends 60s after start.
  - A restart-safe sweep: state comes from the DB, not from memory.
- **Seam 2: frontend page components** (prior art: the existing page tests with MSW). The **media module is mocked**. Cases:
  - Home list sections with badges, the empty state, and the `/vods` route still listing VODs.
  - 송출 flow: the preview, title validation, start calling the API and publishing, the 방송 중 UI, ending with confirmation, and the "종료됨" screen.
  - The permission-denied message, and resuming from `sessionStorage` while `LIVE`.
  - 시청: a `LIVE` broadcast connects and shows "소리 켜기"; "송출 준비 중…" while the connection fails; a switch to "종료된 방송입니다" when polling returns `ENDED`; an `ENDED` broadcast shows no player; unknown → not found.
- **Not automated**: real MediaMTX and WebRTC (cross-container WebRTC is unreliable in CI). Instead, a **manual real-phone checklist** in the README:
  - Android Chrome and iPhone Safari as 송출자 and as 시청자 on the same Wi-Fi;
  - start, watch, camera switch, end, the reload-resume, and killing the tab, which must auto-end within ~70s.

## Out of Scope

- Login, authorization, channels or any persistent 송출자 identity
- Chat (댓글) and donations (next phase), including any push channel (SSE/WebSocket)
- Recording, and turning an ended 방송 into a VOD
- Viewer counts, thumbnails, categories, search
- HLS/LL-HLS playback, transcoding, simulcast or multiple qualities, scaling beyond MediaMTX's direct WebRTC relay
- Mic mute, filters, landscape 송출 layout, screen sharing
- Native apps and RTMP ingest
- Production deployment, TURN servers and NAT traversal beyond the same LAN

## Further Notes

- Without login, anyone can start a 방송. This is accepted for this phase, and abuse controls come with auth. The 송출 키 protects an existing 방송 from hijacking, not the service from spam.
- Direct WebRTC relay limits concurrent 시청자 per 방송. ADR-0002 records HLS/LL-HLS or an SFU as the scaling path.
- Without a TURN server, viewing and publishing work on the same LAN. Over the internet, symmetric NATs may fail. Production needs TURN and real certificates.
- iPhone Safari: H.264 preference keeps playback compatible. If a publisher's browser can't send H.264, iPhone viewers may fail to decode, which the manual checklist covers.
