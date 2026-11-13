# 01: VOD 등록 후 목록에 보이기 (walking skeleton)

**What to build:** A user opens the registration form, enters a title, picks an MP4 영상 파일 and submits. The new VOD then appears as a card on the home list, newest first. The VOD and its 영상 파일 survive a page refresh and a backend restart. This ticket also lays the foundation every other ticket builds on:
- the repository, the backend and frontend skeletons, and the local PostgreSQL
- the `vod` schema migration
- the 영상 파일 storage interface with its LOCAL implementation
- both test seams (the HTTP API seam on Testcontainers, and the frontend component seam)

Stack, schema and API shape follow the spec (`.scratch/vod-mvp/spec.md`). Use the GLOSSARY terms (VOD, 영상 파일, 등록), and never name anything "방송".

**Blocked by:** None (can start immediately).

**Status:** resolved

- [x] The project is a git repository; stored video data, dependencies and build output are git-ignored
- [x] Docker Compose starts PostgreSQL, the backend starts against it, and the frontend dev server proxies `/api` to the backend
- [x] A Flyway migration creates the `vod` table as specified (including the `created_at DESC` index)
- [x] The 영상 파일 storage interface (store / open / delete by storage key) has a LOCAL implementation rooted at a configurable directory; files are saved under generated `videos/{uuid}.mp4` keys, never under the user's filename
- [x] `POST /api/vods` (multipart: title, optional description, file) stores the file, inserts the row, and returns `201` with the VOD body and a `Location` header
- [x] The DB row records `storage_type=LOCAL`, the storage key, the original filename, the content type and the size; no absolute path or URL is stored
- [x] `GET /api/vods` returns VOD summaries (`id`, `title`, `createdAt`) sorted newest first (pagination comes in ticket 05)
- [x] The `/vods/new` form submits a title, a description and a file; on success it navigates to `/`, where the new VOD card (title and registration date, placeholder thumbnail) is shown
- [x] The UI labels the feature "다시보기"
- [x] HTTP API seam test: register a VOD, then list it and see it first
- [x] Frontend seam test: the form renders and submits through a mocked network
