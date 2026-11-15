# 04: 방송 종료와 종료 화면

**What to build:** The 송출자 presses "방송 종료" and confirms. The 방송 becomes 종료, the publisher's media connection is cut, the camera is released, and the 송출 screen shows "종료됨". Only the holder of the 송출 키 can end a 방송; ending twice is harmless.

A 시청자 watching that 방송 sees the screen switch to "종료된 방송입니다" within about 5 seconds. Anyone opening an already-ended 방송 sees its title and start/end times with "종료된 방송입니다" and no player.

**Blocked by:** 02 (휴대폰으로 방송 시작 → 링크로 시청)

**Status:** ready-for-agent

- [ ] `POST /api/broadcasts/{id}/end` with `Authorization: Bearer {publishKey}` → `204`, sets ENDED with `ended_at = now`, and asks the media server gateway to kick the publisher
- [ ] Missing or wrong key → `401`; unknown id → `404`; already-ended → `204` with no state change (all errors Problem Details)
- [ ] After ending, the auth callback denies both publish and read for that 방송, and the detail has no `whepUrl`
- [ ] The 송출 screen's "방송 종료" asks for confirmation, then calls the end API, stops the local tracks, and shows "종료됨"
- [ ] The 시청 screen polls the status every 5s (and immediately when the WebRTC connection drops); on ENDED it tears down the player and shows "종료된 방송입니다" with title and times
- [ ] Opening an ENDED 방송 shows no player and no connection attempt
- [ ] HTTP API seam tests: end with the right key (status, `ended_at`, the fake recorded a kick), wrong or missing key, unknown id, ending twice, and auth deny after end
- [ ] Frontend seam tests: end with confirmation leads to "종료됨"; the viewer switches to "종료된 방송입니다" when polling returns ENDED; an ENDED 방송 has no player
