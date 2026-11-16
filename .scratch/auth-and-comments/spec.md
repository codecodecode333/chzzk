# 회원 인증·인가와 댓글

Status: resolved

## Problem Statement

치지직에서는 지금 누구나 VOD를 등록하고 방송을 시작할 수 있다. 하지만 그것이 누구의 것인지 알 수 없고, 등록한 사람조차 자기 VOD를 고치거나 지울 수 없다. 방송은 송출 키를 가진 브라우저만 제어할 수 있어서, 탭을 닫거나 기기를 바꾸면 자기 방송인데도 손댈 수 없다. 시청자는 보는 것 말고는 할 수 있는 게 없어서, VOD나 방송에 반응을 남길 방법이 없다.

## Solution

사람들은 이메일·비밀번호·닉네임으로 **회원**가입하고, 로그인·로그아웃한다. 로그인한 회원이 VOD를 등록하거나 방송을 시작하면 그 회원이 **소유자**가 된다. 소유자만 VOD를 수정·삭제하고, 방송의 제목을 고치고, 종료된 방송을 삭제하고, 송출하고 종료할 수 있다. 서버가 이 권한을 모든 요청에서 검사한다.

로그인한 회원은 VOD와 방송에 **댓글**을 달 수 있다. 댓글은 **작성자**만 수정·삭제할 수 있다. 시청·목록·댓글 목록은 누구에게나 공개되고, 공개 범위는 README에 표로 설명한다.

## User Stories

### 가입·로그인
1. As a visitor, I want to sign up with my email, a password and a 닉네임, so that I can register VODs, broadcast and comment.
2. As a visitor, I want to be told immediately if my email is invalid, my password is shorter than 8 or longer than 64 characters, or my 닉네임 isn't 2–20 characters, so that I can fix the form before submitting.
3. As a visitor, I want to be told if the email or the 닉네임 is already taken, field by field, so that I know which one to change.
4. As a visitor, I want my email treated case-insensitively, so that "A@x.com" and "a@x.com" can't become two 회원.
5. As a visitor, I want to be logged in right after signing up, so that I don't have to type my credentials twice.
6. As a 회원, I want to log in with my email and password, so that I can act as myself.
7. As a 회원, I want a single "이메일 또는 비밀번호가 올바르지 않습니다" message on a failed login, so that others can't probe which emails are registered.
8. As a 회원, I want to return to the page I was on after logging in, so that logging in doesn't lose my place.
9. As a 회원, I want to log out, so that someone else using my device can't act as me.
10. As a 회원, I want logout to take effect immediately on the server, so that my session can't be reused afterwards.
11. As a 회원, I want to stay logged in across page reloads and server restarts for up to 7 days of inactivity, so that I don't have to log in constantly.
12. As a 회원, I want the header to show my 닉네임 and a 로그아웃 button while logged in, and 로그인/회원가입 while logged out, so that I always know my state.
13. As a visitor, I want to be sent to the login page when I open the VOD 등록 or 방송 시작 screen, so that I understand why I can't use it yet.

### VOD 소유
14. As a 회원, I want to register a VOD and become its 소유자, so that it's recognisably mine.
15. As a visitor, I want registering a VOD to be refused unless I'm logged in, so that every VOD has an owner.
16. As a 시청자, I want to see the 소유자's 닉네임 on VOD cards and the VOD detail page, so that I know who posted it.
17. As a VOD 소유자, I want to edit the title and description of my VOD, so that I can fix mistakes.
18. As a VOD 소유자, I want my edits validated with the same rules as registration, so that VODs stay consistent.
19. As a VOD 소유자, I want to delete my VOD after confirming, so that I can remove content I no longer want shown.
20. As a VOD 소유자, I want deleting a VOD to also remove its 영상 파일 and all its 댓글, so that nothing of it lingers.
21. As a 회원, I want edit and delete controls to appear only on my own VODs, so that the page isn't cluttered with actions I can't take.
22. As a 회원, I want the server to refuse edits and deletes on VODs I don't own even if I call the API directly, so that ownership is real.

### 방송 소유
23. As a 회원, I want to start a 방송 and become its 소유자, so that I control it.
24. As a visitor, I want starting a 방송 to be refused unless I'm logged in, so that every 방송 has an owner.
25. As a 시청자, I want to see the 소유자's 닉네임 on 방송 cards and the 시청 screen, so that I know whose broadcast it is.
26. As a 방송 소유자, I want to end my 방송 from any device where I'm logged in, so that losing the original tab doesn't strand it.
27. As a 방송 소유자, I want to resume publishing my live 방송 from another tab or device, so that switching devices doesn't split my 방송 in two.
28. As a 방송 소유자, I want resuming to issue a fresh 송출 키 and invalidate the old one, so that an old tab or leaked key can't keep publishing.
29. As a 방송 소유자, I want to change my 방송's title while it's live or after it ended, so that I can fix or update it.
30. As a 방송 소유자, I want to delete an ended 방송 after confirming, so that I can remove it from the list.
31. As a 방송 소유자, I want deleting a live 방송 to be refused with a clear message, so that I end it properly first.
32. As a 회원, I want to be stopped from starting a second 방송 while one of mine is live, and offered to resume the live one instead, so that I don't leave a duplicate live 방송.
33. As a 회원, I want 방송 controls (송출, 종료, 수정, 삭제) shown only on my own 방송, so that other 시청자 never see them.
34. As a 회원, I want the server to refuse 종료, 수정, 삭제 and key issuing on 방송 I don't own, so that nobody can hijack my broadcast.
35. As a 방송 소유자, I want my publishing to keep running even if my login session expires mid-broadcast, so that a long broadcast isn't cut by a timeout.
36. As a 방송 소유자, I want to be asked to log in again if my session expired when I press 종료, so that I can still end it.

### 댓글
37. As a 시청자, I want to read the 댓글 on a VOD without logging in, so that I can see what others think.
38. As a 시청자, I want to read the 댓글 on a 방송, live or ended, without logging in, so that I can follow the conversation.
39. As a 시청자, I want 댓글 shown newest first with the 작성자's 닉네임 and time, so that I see recent reactions first.
40. As a 시청자, I want to load older 댓글 20 at a time with "더 보기", so that long threads stay fast.
41. As a 시청자 of a live 방송, I want new 댓글 to appear by themselves every few seconds, so that I can follow along while watching.
42. As a 회원, I want to write a 댓글 (1–500 characters) on a VOD, so that I can react to it.
43. As a 회원, I want to write a 댓글 on a live or ended 방송, so that I can react to it.
44. As a visitor, I want the 댓글 box to show "로그인하고 댓글 달기" linking to login and back, so that I know how to join in.
45. As a 회원, I want my new 댓글 to appear at the top immediately after posting, so that I know it went through.
46. As a 작성자, I want to edit my 댓글, so that I can fix typos.
47. As a 시청자, I want edited 댓글 marked "(수정됨)", so that I know the text changed after posting.
48. As a 작성자, I want to delete my 댓글 after confirming, so that I can take back something I said.
49. As a 회원, I want edit and delete shown only on my own 댓글, and the server to refuse them on others' 댓글, so that nobody can tamper with what I wrote.
50. As a 시청자, I want 댓글 to disappear when their VOD or 방송 is deleted, so that orphaned discussions don't linger.

### 운영·문서
51. As a 운영자, I want passwords stored only as BCrypt hashes, so that a DB leak doesn't expose them.
52. As a 운영자, I want state-changing requests protected by CSRF tokens, so that other sites can't act on behalf of a logged-in 회원.
53. As a 운영자, I want sessions stored in PostgreSQL, so that restarts don't log everyone out.
54. As a 운영자, I want 401 for "not logged in" and 403 for "not allowed", both as Problem Details, so that clients can react correctly.
55. As a reader of the README, I want a table of which actions are public, which need login, and which need ownership or authorship, so that the access policy is explicit.
56. As a developer, I want the migration to clear pre-auth VOD and 방송 rows that have no owner, with the README explaining how to clean leftover 영상 파일, so that ownership can be mandatory.

## Implementation Decisions

**Domain vocabulary** (see `GLOSSARY.md`, updated with a 회원 section and a 시청과 댓글 section): **회원**, **닉네임**, **소유자** (VOD/방송 only), **댓글**, **작성자** (댓글 only). VOD and 방송 stay separate concepts with no umbrella entity; ownership and 댓글 attach to each. The **송출자** is always the 방송's 소유자. The **송출 키** now authenticates only the media connection.

**Authentication** (see ADR-0003): Spring Security with server-side sessions stored in PostgreSQL (Spring Session JDBC).
- The session cookie is HttpOnly and SameSite=Lax, with an idle timeout of 7 days.
- CSRF protection uses a cookie-to-header token: the SPA's HTTP client echoes the token automatically.
- Passwords are hashed with BCrypt.
- No JWT.

**Modules (backend)**
- **Member/auth module** (new feature module): signup, login, logout, and current-member lookup. It owns the email and 닉네임 rules and the password hashing. It exposes "who is the current 회원" to other features through one small accessor, so features never touch the security context directly.
- **Access policy:** URL-level rules allow public reads, require authentication for writes, and leave the MediaMTX internal callback reachable as today (shared secret). Ownership and authorship checks live in each feature's domain service, not in controllers, so the rule sits next to the data it guards.
- **VOD module:** `register(owner, …)`, `update(member, id, title?, description?)`, `delete(member, id)`. Delete removes the row, then the 영상 파일 best-effort (log on failure); 댓글 go with the row by cascade.
- **Broadcast lifecycle:**
  - `start(owner, title)` refuses with a conflict if the owner already has a LIVE 방송.
  - `end(member, id)` checks ownership through the session; it no longer takes a key.
  - `rename(member, id, title)`.
  - `delete(member, id)` works on ENDED only and refuses LIVE with a conflict.
  - `issuePublishKey(member, id)` works on LIVE only. It generates a new key, stores only its hash, and invalidates the previous one.
  - `authorizePublish` keeps verifying the key for the MediaMTX callback.
- **Comments module** (new feature module): `list(target, before, size)`, `write(member, target, content)`, `edit(member, commentId, content)`, `delete(member, commentId)`. The target is "VOD id" or "방송 id". Authorship is checked in the service.
- **Errors:** add 401 (unauthenticated), 403 (not owner/author) and 409 (conflicts) to the shared Problem Details handling, with Korean messages, following the existing conventions.

**API contract** (all errors are RFC 7807 Problem Details)
- **Auth**
  - `GET /api/auth/csrf` issues the CSRF token cookie.
  - `POST /api/auth/signup` `{email, password, nickname}` → `201`, already logged in.
    - Validation failures → `400` with field errors.
    - A duplicate email or 닉네임 → `409` with the field named.
  - `POST /api/auth/login` `{email, password}` → `204`.
    - Bad credentials → `401` with one combined message.
  - `POST /api/auth/logout` → `204`, and the session is invalidated.
  - `GET /api/me` → `{id, nickname}` or `401`.
- **VOD**
  - `POST /api/vods` requires login.
  - `PATCH /api/vods/{id}` `{title?, description?}` is owner only.
  - `DELETE /api/vods/{id}` is owner only and returns `204`.
  - List and detail include `owner: {id, nickname}`.
- **방송**
  - `POST /api/broadcasts` requires login; `409` if the caller already has a LIVE 방송, including that 방송's id so the client can offer resume.
  - `PATCH /api/broadcasts/{id}` `{title}` is owner only.
  - `DELETE /api/broadcasts/{id}` is owner only; `409` while LIVE.
  - `POST /api/broadcasts/{id}/end` takes the owner's session (no Bearer key). It keeps the existing idempotent behaviour for an already-ended 방송.
  - `POST /api/broadcasts/{id}/publish-key` is owner only and LIVE only. It returns `{publishKey, whipUrl}`, and the old key stops working.
  - List and detail include `owner`.
- **댓글**
  - `GET /api/vods/{id}/comments?before=&size=` and `GET /api/broadcasts/{id}/comments?before=&size=` are public.
    - The response is `{content: [{id, content, author:{id, nickname}, createdAt, edited}], nextBefore}`.
    - Ordered by id DESC; `size` defaults to 20 and is capped.
  - `POST /api/vods/{id}/comments` and `POST /api/broadcasts/{id}/comments` require login. The content is trimmed and must be 1–500 chars.
  - `PATCH /api/comments/{id}` `{content}` and `DELETE /api/comments/{id}` are author only.
- **Status codes:** `401` when not logged in, `403` when logged in but not the owner or author, `404` for an unknown target, `409` for conflicts.

**Schema** (Flyway migrations)
- **`member`:**
  - `id`, `email` (unique on the lower-cased value), `password_hash`, `nickname` (unique), `created_at`.
- **Spring Session JDBC tables.**
- **`vod` and `broadcast`:** delete existing rows (all are pre-auth dev data), then add `owner_id BIGINT NOT NULL REFERENCES member`.
- **`broadcast`:** a partial unique index enforces at most one LIVE 방송 per owner. This is the race-safe backstop for the 409.
- **`comment`:**
  - Columns: `id`, `vod_id NULL`, `broadcast_id NULL`, `author_id NOT NULL`, `content VARCHAR(500) NOT NULL`, `created_at`, `updated_at NULL`.
  - A CHECK constraint requires exactly one of `vod_id` and `broadcast_id`.
  - Both FKs are `ON DELETE CASCADE`.
  - Indexes on `(vod_id, id DESC)` and `(broadcast_id, id DESC)`.
  - `edited` is derived from `updated_at`.

**Frontend**
- **Current member state:** loaded from `/api/me` once at app start and refreshed after login, logout and signup. The header shows the 닉네임 and 로그아웃 while logged in, and 로그인 and 회원가입 while logged out.
- **CSRF:** the HTTP client fetches the CSRF token before the first write and sends it back automatically.
- **Routes:**
  - `/signup` and `/login`, which honour `?next=` limited to in-app paths.
  - VOD 등록 and 방송 시작 screens redirect to login when logged out.
- **Owner-only controls:**
  - **VOD detail:** 수정 (an inline title and description form) and 삭제 (with confirmation, then back to the list).
  - **방송 시청 screen:** 제목 수정, plus 삭제 for ENDED (with confirmation) and a link to the 송출 screen while LIVE.
  - Pages hide these for non-owners. The server remains the authority, and a `403` is shown as a message.
- **송출 screen:**
  - **Removed:** the 송출 키 is no longer kept in `sessionStorage`.
  - **Resume:** on load, if the logged-in 회원 has a LIVE 방송, the screen offers "이어서 송출". This requests a fresh key via the publish-key API, then republishes.
  - **Starting a second 방송:** a 409 on start shows the same resume offer.
  - **Ending:** uses the session.
  - **Expired session:** a 401 while live asks the 회원 to log in again and keeps the 방송 running.
- **댓글 section** (on VOD detail and the 방송 시청 screen, for live and ended 방송):
  - **List:** newest first, with "더 보기" using a cursor.
  - **Write box:** shown to 회원; visitors see "로그인하고 댓글 달기".
  - **After posting:** the new 댓글 appears at the top.
  - **Author controls:** inline edit and delete with confirmation; edited 댓글 show "(수정됨)".
  - **Refresh:** on a LIVE 방송 the section fetches newer 댓글 every 5s. VOD and ENDED 방송 sections don't refresh on their own.
  - **Component:** one shared component for both targets.

**README**: an access-policy table covering each action, who may do it, and the response when not allowed. It also documents the migration's data reset and how to clean leftover 영상 파일 in local storage.

## Testing Decisions

- Good tests exercise external behaviour only: HTTP in and out on the backend, rendered UI and interaction on the frontend. They never inspect security internals, sessions or tables directly. The existing VOD and 방송 API tests and page tests are the model.
- **Seam 1: backend HTTP API** (prior art: the existing VOD and 방송 API tests with Testcontainers PostgreSQL, the fake media server gateway and the injectable clock).
  - **Setup:** tests sign up and log in **through the real auth API**, carrying the session cookie and CSRF token, and act as that 회원. A small test helper wraps this.
  - **Auth cases:**
    - signup success (logged in afterwards), validation, duplicate email (case-insensitive) and 닉네임;
    - login success, bad credentials with one message, logout invalidating the session, `/api/me`;
    - a write without a CSRF token is rejected.
  - **Authorization matrix** — for each protected action, as anonymous (401), another 회원 (403) and the owner or author (success):
    - VOD register, update and delete;
    - 방송 start, rename, end, delete (LIVE → 409) and publish-key (rotation invalidates the old key in the MediaMTX auth callback);
    - 댓글 write, edit and delete.
  - **Behaviour cases:**
    - A second live 방송 → 409 with the existing id.
    - Deleting a VOD removes its 영상 파일 and 댓글.
    - Deleting a 방송 removes its 댓글.
    - 댓글 paging with a cursor, ordering, the `edited` flag, the content length rule, and comments on an ENDED 방송 allowed.
    - Public reads work without login.
- **Seam 2: frontend page components** (prior art: the existing page tests with MSW; the media module stays mocked). `/api/me` is mocked per test to switch between visitor, 회원 and owner.
  - **Auth pages:**
    - signup and login forms with their validation and server errors;
    - redirect to `next` after login, and the guard redirect for protected screens;
    - the header showing each state, and logout.
  - **Owner controls:** VOD 수정 and 삭제 shown only to the owner, with the edit flow and delete with confirmation; the same for 방송 rename and delete.
  - **송출 screen:** the resume offer for a LIVE 방송 owned by the 회원 (requests a new key, then publishes); a 409 on start shows the resume offer; a 401 on end asks to log in.
  - **댓글 section:**
    - the visitor sees "로그인하고 댓글 달기", and a 회원 writes a comment that appears at the top;
    - "더 보기" loads older comments; edit and delete appear only for the author; "(수정됨)" is shown;
    - live polling picks up new comments (fake timers).
- **Not automated:** none beyond the existing real-phone checklist. The checklist gains a step: log in as the 송출자, and verify that a second phone logged in as another 회원 sees no controls.

## Out of Scope

- Email verification, password reset, changing email, password or 닉네임, and account deletion
- Social login, "로그인 상태 유지" beyond the 7-day idle session, and multi-factor auth
- Login lockout, rate limiting, CAPTCHA
- Admin roles, and owners moderating others' 댓글
- 댓글 replies, reactions, mentions, reporting; real-time push (SSE/WebSocket)
- Profiles, "내 VOD/내 방송" pages, following, subscriptions
- Private or unlisted VODs and 방송 (everything is public to view)
- Transferring ownership
- Donations

## Further Notes

- The migration deletes all existing VOD, 방송 and (implicitly) 댓글 rows because they predate ownership and only dev data exists. Leftover 영상 파일 in local storage must be cleaned manually; the README says how.
- The MediaMTX auth callback still cannot see browser cookies, so WHIP stays key-authenticated. Keys are now issued only to the logged-in owner and rotate on resume. The existing shared-secret protection of the internal callback is unchanged.
- The 60-second auto-end still applies, so a 방송 whose owner's session expired keeps running only while media flows.
- SameSite=Lax plus CSRF tokens cover the same-origin SPA. A cross-origin client would need a revisit (ADR-0003).
