# 07: README 공개 범위 표와 마무리 문서

**What to build:** A reader of the README can see at a glance who may do what. Add an access-policy table listing every action:
- signup, login, logout
- list and view VOD and 방송, play and watch
- 댓글 list, write, edit and delete
- VOD register, edit and delete
- 방송 start, end, resume (key), rename and delete

For each action, the table states who may do it (누구나 / 로그인 회원 / 소유자 / 작성자) and what the server answers otherwise (`401`/`403`/`409`). The README also explains:
- that the auth migrations wiped pre-existing VOD and 방송 rows, and how to clean leftover 영상 파일 in local storage;
- how to run the app with login (nothing extra beyond the existing `.env`, if that's the case).

The real-phone checklist gains a step: a second phone logged in as a different 회원 sees no 송출, 수정 or 삭제 controls on someone else's 방송.

**Blocked by:** 02 (VOD 소유자), 04 (방송 제목 수정과 삭제), 06 (방송 댓글)

**Status:** resolved

- [x] The README has a 공개 범위 section with the access-policy table above, matching the actual server behaviour (verify against the tests and controllers, not the spec alone)
- [x] The README states that viewing, lists and 댓글 lists are public with no login, and why
- [x] The README documents the data reset from the migrations and the 영상 파일 cleanup steps
- [x] The real-phone checklist includes the second-회원 check
- [x] The README run and test instructions are still accurate (run the tests once to confirm)
