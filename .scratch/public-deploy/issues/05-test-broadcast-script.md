# 05: 테스트 방송 스크립트

**What to build:** A script that puts a 방송 on air without a phone: headless Edge/Chrome with a fake camera/microphone logs in (or signs up) a test 회원 on a given base URL, starts a 방송 and 송출s through the site's own studio flow, and keeps it live until stopped, printing the 방송 id. Works against the local dev site (self-signed OK) and the public site. See spec.

**Blocked by:** None - can start immediately

**Status:** ready-for-agent

- [ ] Script with base URL, credentials and duration options; no heavy new dependencies (drive the installed browser through the DevTools protocol)
- [ ] Verified locally: the 방송 shows 방송 중 in the list and a viewer receives video
- [ ] README documents usage
