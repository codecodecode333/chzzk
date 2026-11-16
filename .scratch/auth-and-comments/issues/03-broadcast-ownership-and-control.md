# 03: 방송 소유자와 송출 제어

**What to build:** Only a logged-in 회원 can start a 방송, and that 회원 becomes its 소유자 (and so its 송출자). The 소유자's 닉네임 shows on 방송 cards and the 시청 screen. A 회원 can have at most one live 방송: trying to start another offers "진행 중인 방송 이어서 송출" instead.

The 소유자 ends the 방송 using their login session, from any device. They can resume their live 방송 on another tab or device; resuming issues a fresh 송출 키 and the old one stops working. The 송출 키 is no longer kept in `sessionStorage`. If the session expired, pressing 종료 asks the 소유자 to log in again, and publishing continues meanwhile. The MediaMTX callback keeps authenticating WHIP by key.

**Blocked by:** 01 (회원가입·로그인·로그아웃)

**Status:** resolved

- [x] A migration deletes existing `broadcast` rows (pre-auth dev data), adds `owner_id NOT NULL REFERENCES member`, and adds a partial unique index allowing at most one LIVE 방송 per owner
- [x] `POST /api/broadcasts` requires login (`401`) and records the 소유자. If the caller already has a LIVE 방송 → `409` Problem Details including that 방송's id (the race-safe backstop is the unique index). List and detail include `owner: {id, nickname}`
- [x] `POST /api/broadcasts/{id}/end` authorizes by the 소유자's session (the Bearer key is no longer accepted or needed). Anonymous → `401`, another 회원 → `403`. An already-ended 방송 behaves as before
- [x] `POST /api/broadcasts/{id}/publish-key` is 소유자 only and LIVE only (ENDED → `409`). It returns `{publishKey, whipUrl}`, stores only the new hash, and the previous key is rejected by the MediaMTX auth callback from then on
- [x] Ownership checks live in the broadcast lifecycle, via the current-회원 accessor; the existing auto-end, race-safety and MediaMTX shared secret are unchanged
- [x] The 송출 screen redirects to login when logged out. On load, if the 회원 has a LIVE 방송, it offers "이어서 송출", which requests a new key and republishes. A `409` on start shows the same offer. `sessionStorage` key storage is removed
- [x] Ending uses the session. A `401` while live shows "다시 로그인" (with `next` back to the 송출 screen) without stopping publishing
- [x] 방송 cards and the 시청 screen show the 소유자 닉네임; 송출 and 종료 controls are never shown to non-소유자
- [x] HTTP API seam tests cover: the start/end/publish-key authorization matrix; the second-live `409` with the id; key rotation invalidating the old key in the auth callback; and existing 방송 tests passing through the login helper
- [x] Frontend seam tests cover: the login guard, the resume offer on load and on a `409`, resume requesting a new key then publishing, and a `401` on end asking to log in
- [x] The README real-phone checklist is updated for login: the 송출자 logs in; resume on a second device as the same 회원
