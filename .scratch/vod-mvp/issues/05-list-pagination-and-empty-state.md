# 05: 목록 페이지 나누기와 빈 상태

**What to build:** The home VOD list shows 20 VODs per page, newest first, with controls to move between pages, so the page stays usable as VODs accumulate. With no VODs at all, the list shows "아직 등록된 다시보기가 없습니다" and a button to the registration form instead of a blank grid.

**Blocked by:** 01 (VOD 등록 후 목록에 보이기)

**Status:** ready-for-agent

- [ ] `GET /api/vods?page=&size=` returns an offset page with `content`, `page`, `size`, `totalElements` and `totalPages`; the size defaults to 20, is capped at a sane maximum, and the order is `createdAt DESC`
- [ ] An out-of-range page returns an empty `content`, not an error; a negative page or invalid size returns `400` Problem Details
- [ ] The list UI shows pagination controls when there is more than one page, and the current page is reflected in the URL so it survives refresh and sharing
- [ ] The empty state shows the message and a register button linking to `/vods/new`
- [ ] HTTP API seam tests cover: newest-first ordering across a page boundary (e.g. 21 VODs give pages of 20 and 1), the totals, and the out-of-range and invalid params
- [ ] Frontend seam test: an empty response renders the empty state with the register button
