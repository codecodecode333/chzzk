# 02: 방송별 후원 내역과 합계

**What to build:** Anyone, logged in or not, opening a 방송's 시청 screen sees a 후원 panel above the comments, live or ended. The panel shows:
- the 방송's 후원 합계
- confirmed 후원, newest first, with each 후원자's 닉네임, amount, 후원 메시지 and time
- "더 보기" to load 20 older 후원 at a time

While the 방송 is live, new 후원 and the 합계 appear on their own every 5 seconds. Only confirmed 후원 ever appear or count.

**Blocked by:** 01 (후원 주문과 서버 확정)

**Status:** resolved

- [x] `GET /api/broadcasts/{id}/donations?before=&after=&size=` is public and returns `{content:[{id, donor:{id,nickname}, amount, message, confirmedAt}], nextBefore, nextAfter, total}` with CONFIRMED only, ordered newest first; `size` defaults to 20 and is capped, the cursors behave like 댓글's, and an unknown 방송 → `404`
- [x] `total` sums CONFIRMED amounts only, so PENDING, FAILED and EXPIRED never count
- [x] The 시청 screen panel shows the 합계 and the 내역 with "더 보기". While LIVE it polls every 5s with the `after` cursor and refreshes the 합계, with no duplicates and without losing loaded pages; it stops when the 방송 ends
- [x] After a successful 후원, returning to the 방송 shows the new 후원 in the panel
- [x] HTTP API seam tests cover: public access, CONFIRMED-only filtering (seed PENDING, FAILED and CONFIRMED through the flow), ordering, both cursors, the total, and unknown 방송
- [x] Frontend seam tests cover: 합계 and 내역 rendering, "더 보기", LIVE polling picking up a new 후원 (fake timers), and ENDED not polling
