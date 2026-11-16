# 02: VOD 소유자, 수정, 삭제

**What to build:** Only a logged-in 회원 can register a VOD, and the registering 회원 becomes its 소유자. Every VOD card and detail page shows the 소유자's 닉네임.

The 소유자 can edit the title and description inline on the detail page, with the same rules as registration. The 소유자 can also delete the VOD after confirming; this removes the 영상 파일 (best effort) and all its 댓글 (by cascade, once comments exist), then returns to the list. Edit and delete controls appear only to the 소유자. The server refuses everyone else with 401 or 403. Opening the registration screen while logged out redirects to login.

**Blocked by:** 01 (회원가입·로그인·로그아웃)

**Status:** ready-for-agent

- [ ] A migration deletes existing `vod` rows (pre-auth dev data) and adds `owner_id NOT NULL REFERENCES member`
- [ ] `POST /api/vods` requires login (`401` otherwise) and records the caller as 소유자; list and detail include `owner: {id, nickname}`
- [ ] `PATCH /api/vods/{id}` `{title?, description?}`: the 소유자 gets `200` with the updated VOD. Validation is the same as registration (`400`). Anonymous → `401`, another 회원 → `403`, unknown id → `404`
- [ ] `DELETE /api/vods/{id}`: the 소유자 gets `204`, the row is removed, and then the 영상 파일 is deleted best-effort (a failure is logged and the delete still succeeds). Anonymous → `401`, another 회원 → `403`, unknown id → `404`
- [ ] Ownership checks live in the VOD domain service, using the current-회원 accessor
- [ ] The VOD registration screen redirects to login (with `next`) when logged out
- [ ] VOD cards and the detail page show the 소유자 닉네임. 수정 (inline form) and 삭제 (with confirmation, then navigate to `/vods`) appear only for the 소유자, and a server `403` is shown as a message
- [ ] HTTP API seam tests cover the authorization matrix (anonymous, other 회원, 소유자) for register, update and delete; the 영상 파일 being gone after delete; and `owner` in the responses. Existing VOD tests log in through the helper
- [ ] Frontend seam tests cover: the registration guard redirect, controls visible only to the 소유자, the edit flow, and delete with confirmation
