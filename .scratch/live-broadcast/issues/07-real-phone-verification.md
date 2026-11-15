# 07: 실기기 검증

**What to build:** A person with real phones confirms the live broadcast works end to end on the same Wi-Fi, following the real-phone checklist in the README. WebRTC through a real MediaMTX isn't covered by automated tests (see the spec's Testing Decisions), so this ticket is the acceptance gate for that path. Problems found here become new tickets.

**Blocked by:** 03 (방송 목록 홈), 04 (방송 종료), 05 (자동 종료·이어서 송출·송출 준비 중), 06 (송출 화면 다듬기)

**Status:** ready-for-human

- [ ] The README contains the real-phone checklist covering every item below
- [ ] Android Chrome as 송출자 → iPhone Safari and a desktop browser as 시청자: start, watch with sub-second delay, "소리 켜기", camera switch while live, end, and 시청자 sees "종료된 방송입니다"
- [ ] iPhone Safari as 송출자 → Android Chrome as 시청자: the same flow
- [ ] Reload the 송출 page while live and "이어서 송출" within 60s: the same 방송 continues and 시청자 recover
- [ ] Kill the 송출 tab: the 방송 shows 종료 in the list and for 시청자 within about 70s
- [ ] The list shows 방송 중 and 종료 badges correctly throughout
