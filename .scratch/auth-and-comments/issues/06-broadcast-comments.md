# 06: 방송 댓글과 라이브 갱신

**What to build:** The same 댓글 experience appears on the 방송 시청 screen, for both live and ended 방송. Anyone reads; logged-in 회원 write; 작성자 edit and delete their own. While a 방송 is live, newer 댓글 appear on their own every 5 seconds, so 시청자 can follow along. Ended 방송 and VODs don't refresh on their own. Deleting a 방송 removes its 댓글.

**Blocked by:** 03 (방송 소유자와 송출 제어), 05 (VOD 댓글)

**Status:** ready-for-agent

- [ ] `GET /api/broadcasts/{id}/comments?before=&size=` is public, with the same shape, ordering and paging as VOD 댓글; unknown 방송 → `404`
- [ ] `POST /api/broadcasts/{id}/comments` requires login (`401`), uses the same content rule, and is allowed on LIVE and ENDED 방송
- [ ] `PATCH` and `DELETE /api/comments/{id}` work for 방송 댓글 with the same 작성자 checks
- [ ] Deleting a 방송 removes its 댓글 (cascade). If ticket 04 has landed, cover it in its delete test; otherwise cover it here at the DB/API level
- [ ] The 시청 screen embeds the shared comment section for the 방송. While LIVE it fetches newer 댓글 every 5s and merges them at the top without duplicates or losing loaded older pages; polling stops when the 방송 ends
- [ ] HTTP API seam tests cover: public list, write as anonymous/회원 (on LIVE and ENDED), edit and delete as other 회원 and 작성자 on a 방송 댓글
- [ ] Frontend seam tests cover: the section on a LIVE 방송 picking up a new 댓글 via polling (fake timers); an ENDED 방송 showing 댓글 with no polling; the visitor prompt
