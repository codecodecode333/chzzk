# VOD MVP: 로그인 없이 다시보기 등록·시청

Status: resolved

## Problem Statement

치지직은 방송 플랫폼을 목표로 하지만, 아직 녹화된 영상을 올려 두고 다른 사람이 볼 수 있는 기본 경로가 없다. 사용자는 녹화 영상을 올리고, 등록된 다시보기를 둘러보고, 골라서 재생할 수 있어야 한다. 이번 단계에서는 로그인이나 라이브 같은 부가 기능은 필요 없고, 등록하고 다시 찾아 볼 수 있는 것만 있으면 된다.

## Solution

로그인 없이 누구나 MP4 영상 파일과 제목(그리고 선택 설명)을 올려 **VOD**를 **등록**한다. 등록된 VOD는 최신순 목록에 카드로 나타나고, 카드를 누르면 상세 화면에서 영상을 바로 재생·탐색할 수 있다. VOD 정보와 영상 파일의 저장 위치는 서버에 영속 저장되어, 서버가 재시작돼도 다시 조회·재생된다.

## User Stories

1. As a 시청자, I want to see a list of registered VODs on the home page, so that I can find something to watch.
2. As a 시청자, I want the VOD list ordered newest first, so that I see the latest recordings first.
3. As a 시청자, I want each VOD card to show its title and registration date, so that I can tell VODs apart.
4. As a 시청자, I want the list split into pages of 20, so that the page stays fast as VODs accumulate.
5. As a 시청자, I want to move between list pages, so that I can browse older VODs.
6. As a 시청자, I want a clear message and a register button when there are no VODs yet, so that I'm not confused by an empty page.
7. As a 시청자, I want to click a VOD card to open its detail page, so that I can watch it.
8. As a 시청자, I want the detail page to show the title, description, and registration date, so that I know what I'm watching.
9. As a 시청자, I want the video to play directly in the detail page, so that I don't need to download it.
10. As a 시청자, I want to seek to any point in the video, so that I can skip to the part I care about.
11. As a 시청자, I want playback to start without waiting for the whole file to download, so that large recordings are watchable immediately.
12. As a 시청자, I want a "not found" page when I open a VOD that doesn't exist, so that I know the link is wrong rather than broken.
13. As a 시청자, I want a "다시보기를 재생할 수 없습니다" message when the video can't play, so that I'm not left staring at a black box.
14. As a 시청자, I want VOD detail pages to have stable URLs, so that I can share or revisit a specific VOD.
15. As a 등록자, I want to register a VOD without logging in, so that I can share a recording with no setup.
16. As a 등록자, I want to pick an MP4 file from my computer, so that I can register a recording I already have.
17. As a 등록자, I want to enter a title for the VOD, so that viewers know what it is.
18. As a 등록자, I want to optionally enter a description, so that I can give viewers more context.
19. As a 등록자, I want to be told immediately if the title is empty or longer than 100 characters, so that I can fix it before uploading.
20. As a 등록자, I want to be told if the description exceeds 2,000 characters, so that I can shorten it.
21. As a 등록자, I want to be told if my file isn't an MP4, so that I don't wait for a doomed upload.
22. As a 등록자, I want to be told if my file is larger than 1GB, so that I understand why it was rejected.
23. As a 등록자, I want to see upload progress, so that I know a large upload is still going.
24. As a 등록자, I want the submit button disabled while uploading, so that I don't accidentally register twice.
25. As a 등록자, I want to land on the new VOD's detail page after a successful registration, so that I can confirm it plays.
26. As a 등록자, I want my title and description kept in the form if the upload fails, so that I can retry without retyping.
27. As a 등록자, I want a clear error message when registration fails on the server, so that I know whether to retry.
28. As a 등록자, I want duplicate titles to be allowed, so that I'm not blocked by someone else's naming.
29. As a 시청자, I want every VOD in the list to be playable, so that I never open a VOD whose video is missing because of a half-finished registration.
30. As a 운영자, I want registered VODs and their video file locations to survive a server restart, so that content isn't lost.
31. As a 운영자, I want video file locations stored independent of absolute disk paths or public URLs, so that storage can later move to object storage without rewriting data.
32. As a 운영자, I want uploaded files stored under generated names rather than user-supplied names, so that filenames can't collide or escape the storage directory.
33. As a 운영자, I want the original filename, content type, and size recorded, so that I can audit what was uploaded.

## Implementation Decisions

**Domain vocabulary** (see `GLOSSARY.md`): the registered unit is a **VOD** (UI label "다시보기"); it owns exactly one **영상 파일**. "방송" is reserved for future live broadcasts and must not name anything in this feature.

**Stack and layout**: a single repository with a backend (Spring Boot 3, Java 21, Gradle Kotlin DSL) and a frontend (React, Vite, TypeScript, Tailwind CSS). Local development runs only PostgreSQL through Docker Compose. The backend and frontend run natively, and the Vite dev server proxies `/api` to the backend. A git repository is initialized, and stored video data, dependencies and build output are git-ignored.

**Modules (backend)**
- **VOD registration and query** (deep module): exposes `register(title, description, fileStream, metadata) → VOD`, `list(page) → Page<VOD summary>` and `get(id) → VOD`. It owns validation and the save-file-then-insert-row ordering with compensation.
- **Video storage** (interface with one LOCAL implementation): `store(stream) → storageKey`, `open(storageKey) → readable resource with length`, `delete(storageKey)`. Keys look like `videos/{uuid}.mp4`. The LOCAL implementation is rooted at a configurable data directory. The interface exists so object storage can replace it later.
- **HTTP layer**: REST controllers plus RFC 7807 Problem Details error mapping.

**Registration semantics**
- One `multipart/form-data` request carries `title`, an optional `description`, and `file`.
- Validation: the title is trimmed and must be 1–100 chars (whitespace-only is rejected). The description is optional, up to 2,000 chars. The file must have a `.mp4` extension, carry an `ftyp` box signature in its first bytes, and be ≤ 1GB. The codec is not inspected.
- Ordering: the file goes to storage first, then the row is inserted. If the insert fails, the stored file is deleted. As a result, a listed VOD always has a stored 영상 파일.
- No edit or delete in this phase; a registered VOD is immutable.

**API contract**
- `POST /api/vods` (multipart) → `201` with the VOD body and a `Location` header. Errors: `400` for validation, `413` for oversize, `415` for a non-MP4 file. All errors are Problem Details.
- `GET /api/vods?page=0&size=20` → offset page: `{ content: [VOD summary], page, size, totalElements, totalPages }`, sorted by `createdAt DESC`. A summary has `id, title, createdAt`.
- `GET /api/vods/{id}` → `{ id, title, description, createdAt, streamUrl }`, or `404`. `streamUrl` is built by the server (`/api/vods/{id}/stream`) and never stored.
- `GET /api/vods/{id}/stream` → serves the 영상 파일 with HTTP Range support: `206 Partial Content` for range requests, `200` otherwise, `Accept-Ranges: bytes`, `Content-Type: video/mp4`. Returns `404` if the VOD or its stored file is missing.

**Schema** (managed by Flyway): a single `vod` table with `id` (BIGINT identity), `title` (VARCHAR 100, not null), `description` (TEXT, nullable), `storage_type` (VARCHAR; only `LOCAL` for now), `storage_key` (VARCHAR, unique), `original_filename`, `content_type`, `size_bytes` (BIGINT) and `created_at` (TIMESTAMPTZ). An index on `created_at DESC` backs the list order. The 영상 파일 columns live on the VOD row because the relationship is 1:1.

**Playback** (see ADR-0001): progressive MP4 over HTTP Range, played by the native `<video controls>` element. No HLS or transcoding, so a VOD is playable the moment it is registered and has no processing state.

**Frontend routes**
- `/` is the VOD list: a card grid with placeholder thumbnails, title and registration date, plus pagination and an empty state.
- `/vods/new` is the registration form, which mirrors the server validation and shows upload progress via XHR/axios `onUploadProgress`. The submit button is disabled while uploading, inputs are preserved when an upload fails, and success redirects to the detail page.
- `/vods/:id` is the detail page with the player. It shows a 404 view for unknown ids and a playback-error message on the `<video>` error event.

## Testing Decisions

- Good tests exercise external behavior only: HTTP requests and responses on the backend, and rendered UI plus user interaction on the frontend. They do not assert on internal classes, repositories or storage layout, so that refactoring inside a module never breaks a test.
- **Seam 1: Backend HTTP API.** The full Spring context runs against Testcontainers PostgreSQL, with video storage rooted in a temp directory. Cases:
  - registration success: the response body and `Location` header, and the VOD is then retrievable and streamable;
  - title and description validation failures;
  - a non-MP4 file (wrong extension, and a spoofed extension without `ftyp`);
  - an oversize file;
  - list order newest-first and pagination boundaries;
  - detail `404`;
  - stream `200` without Range, `206` with a correct `Content-Range` and body slice, and `404` when the stored file is missing;
  - compensation: when the DB insert fails, no orphaned file remains.

  The 1GB limit is asserted by configuring a smaller limit in tests rather than uploading 1GB.
- **Seam 2: Frontend page components.** Vitest plus Testing Library, with the network mocked at the fetch/XHR boundary. Cases: registration-form validation messages and disabled submit, and the list empty state.
- No browser E2E in this phase.
- Prior art: none. The repository is empty, and these tests establish the patterns.

## Out of Scope

- Authentication and authorization, and therefore any notion of VOD ownership
- Live broadcasting (방송), comments, donations
- Editing or deleting VODs
- Thumbnails, duration extraction, view counts
- HLS, transcoding, multiple qualities, codec validation
- Upload cancel or resumable uploads, presigned/direct-to-storage uploads
- Object storage implementations (only LOCAL ships)
- Deployment images and production infrastructure
- Browser E2E tests

## Further Notes

- Without auth, anyone can register VODs. This is accepted for this phase. Abuse controls such as rate limiting arrive with auth.
- Because codecs aren't validated, an MP4 the browser can't decode (for example H.265) can be registered. It surfaces as the playback-error message, not as a registration failure (ADR-0001).
- The storage interface and the `storage_type` column are the deliberate seams for moving to object storage later. Keep 영상 파일 access behind them.
