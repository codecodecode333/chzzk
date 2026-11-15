# 03: 방송 목록 홈 + 다시보기 이전

**What to build:** The home page `/` becomes the 방송 목록:
- 방송 중 broadcasts are on top (newest started first), each with a clear 방송 중 badge.
- 종료 broadcasts are below (most recently ended first, paged), each with a 종료 badge and end time.
- When nobody is broadcasting, a friendly empty state appears.
- The list refreshes every 10 seconds.
- Tapping a card opens `/broadcasts/:id`.

The existing 다시보기 list moves to `/vods` with no behaviour change. The navigation shows "라이브" and "다시보기", plus entry points to start a 방송 and to register a VOD.

**Blocked by:** 02 (휴대폰으로 방송 시작 → 링크로 시청)

**Status:** ready-for-agent

- [ ] `GET /api/broadcasts?status=LIVE|ENDED&page=&size=` returns the same page wrapper as the VOD list; LIVE is ordered `startedAt DESC`, ENDED `endedAt DESC`; the size default and cap match VOD; a missing or invalid status or bad paging → `400` Problem Details
- [ ] `/` renders a 방송 중 section and a paged 종료 section with status badges, titles, and start/end times; the page number is in the URL
- [ ] There is an empty state when no 방송 exists, and a 방송 중-specific empty message when none are live
- [ ] The list polls every 10s and reflects newly started and newly ended 방송 without a reload
- [ ] The 다시보기 list lives at `/vods`; VOD detail and registration routes and behaviour are unchanged; navigation links to 라이브 (`/`) and 다시보기 (`/vods`)
- [ ] HTTP API seam tests cover per-status ordering, paging across a boundary, and invalid params
- [ ] Frontend seam tests cover: sections and badges, the empty state, polling picking up a status change (fake timers), and `/vods` still listing VODs
