# 05: 자동 종료, 새로고침 이어서 송출, 송출 준비 중

**What to build:** The service survives real-world interruptions:
- **Auto-end.** If a 송출자's phone closes the tab, loses network, or dies, the 방송 becomes 종료 on its own once no media has arrived for 60 consecutive seconds. That includes a 방송 that never started sending media.
- **Resume.** If the 송출자 accidentally reloads or briefly leaves and returns within 60 seconds, they can "이어서 송출" into the **same** 방송. Leaving the page while live warns them first.
- **Waiting.** A 시청자 who opens a 방송 that is 방송 중 but whose media isn't flowing yet (just started, or the publisher is reconnecting) sees "송출 준비 중…". The player retries every 3 seconds until media arrives or the 방송 ends.

**Blocked by:** 04 (방송 종료와 종료 화면)

**Status:** resolved

- [x] A publishing monitor runs every 10s: it asks the media server gateway which 방송 are publishing, records `last_publishing_at` for them, and then sweeps
- [x] The sweep sets ENDED for any LIVE 방송 whose last publishing time (or start time, if it never published) is more than 60s ago, with `ended_at` = that last publishing time (or the start time)
- [x] The sweep reads state from the DB (restart-safe) and uses an injectable clock
- [x] The MediaMTX gateway implementation reads MediaMTX's control API path list; liveness never depends on runOnReady/NotReady hooks
- [x] The 송출 screen stores `{id, publishKey}` in `sessionStorage` on start and clears it on end
- [x] On load, if a stored 방송 is still LIVE, the 송출 screen offers "이어서 송출", which republishes to the same 방송; if it has ended, it clears the stored 방송 and offers a fresh start
- [x] `beforeunload` warns while live
- [x] The 시청 screen shows "송출 준비 중…" and retries WHEP every 3s while the status is LIVE and the connection fails; it stops retrying on ENDED
- [x] HTTP API seam tests (fake gateway and clock): a publishing 방송 stays LIVE as time advances; once it stops being reported it ends after more than 60s with the right `ended_at`; a never-published 방송 ends 60s after start
- [x] Frontend seam tests: resume is offered and republishes when the stored 방송 is LIVE; nothing is offered when it is ENDED; the viewer shows "송출 준비 중…" while the connection fails and recovers when it succeeds
