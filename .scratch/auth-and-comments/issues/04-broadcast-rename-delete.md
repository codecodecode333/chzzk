# 04: 방송 제목 수정과 삭제

**What to build:** The 방송's 소유자 can change its title, whether it is live or ended, from the 시청 screen. The 소유자 can delete an ended 방송 after confirming, which removes it from the list (and its 댓글, once comments exist). Deleting a live 방송 is refused with a clear message to end it first. These controls appear only to the 소유자, and the server refuses everyone else.

**Blocked by:** 03 (방송 소유자와 송출 제어)

**Status:** ready-for-agent

- [ ] `PATCH /api/broadcasts/{id}` `{title}`: the 소유자 gets `200`, with the same title rule as start (`400`). Anonymous → `401`, another 회원 → `403`, unknown id → `404`; works on LIVE and ENDED
- [ ] `DELETE /api/broadcasts/{id}`: the 소유자 gets `204` for an ENDED 방송. LIVE → `409` "방송을 먼저 종료하세요"-style Problem Details; anonymous → `401`, another 회원 → `403`, unknown id → `404`
- [ ] Checks live in the broadcast lifecycle
- [ ] The 시청 screen, for the 소유자, shows 제목 수정 (inline) on any 방송, 삭제 (with confirmation, then back to `/`) on ENDED 방송, and a link to the 송출 screen while LIVE. Non-소유자 see none of these; a server `403` or `409` is shown as a message
- [ ] HTTP API seam tests cover the authorization matrix for rename and delete, and delete of a LIVE 방송 → `409`
- [ ] Frontend seam tests cover: controls only for the 소유자, the rename flow, delete with confirmation on ENDED, and no delete on LIVE
