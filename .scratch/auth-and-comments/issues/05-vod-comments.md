# 05: VOD 댓글

**What to build:** Anyone can read the 댓글 on a VOD's detail page: newest first, with the 작성자's 닉네임, the time, and "(수정됨)" when edited. "더 보기" loads 20 older 댓글 at a time. A logged-in 회원 writes a 댓글 (1–500 characters) and sees it appear at the top immediately; visitors see "로그인하고 댓글 달기" linking to login and back. The 작성자 can edit and delete (with confirmation) their own 댓글 only. The server refuses writes from visitors (`401`) and edits or deletes by anyone but the 작성자 (`403`).

The comments module and the comment section component are built target-agnostic, so ticket 06 can attach them to 방송.

**Blocked by:** 01 (회원가입·로그인·로그아웃)

**Status:** resolved

- [x] A migration creates `comment`:
  - `id`, `vod_id NULL`, `broadcast_id NULL`, `author_id NOT NULL`, `content VARCHAR(500)`, `created_at`, `updated_at NULL`
  - a CHECK requiring exactly one target
  - both FKs `ON DELETE CASCADE`
  - indexes `(vod_id, id DESC)` and `(broadcast_id, id DESC)`
- [x] A comments module with `list(target, before, size)`, `write(member, target, content)`, `edit(member, id, content)` and `delete(member, id)`; authorship is checked in the service
- [x] `GET /api/vods/{id}/comments?before=&size=` is public and returns `{content: [{id, content, author:{id,nickname}, createdAt, edited}], nextBefore}`, ordered by id DESC, `size` default 20 and capped. Unknown VOD → `404`
- [x] `POST /api/vods/{id}/comments` `{content}` requires login (`401`); the content is trimmed to 1–500 chars (`400`); returns `201` with the 댓글
- [x] `PATCH /api/comments/{id}` `{content}` and `DELETE /api/comments/{id}` are 작성자 only: anonymous → `401`, another 회원 → `403`, unknown → `404`. Edit sets `edited: true`
- [x] Deleting a VOD removes its 댓글 (cascade)
- [x] A shared comment section component (target-agnostic) on the VOD detail page covers: the list, "더 보기", the write box for 회원 or the visitor prompt, the new 댓글 at the top, author-only inline edit and delete with confirmation, and "(수정됨)"
- [x] HTTP API seam tests cover: public list and paging with a cursor, ordering, write/edit/delete as anonymous, other 회원 and 작성자, the content rule, `edited`, and the cascade on VOD delete
- [x] Frontend seam tests cover: the visitor prompt, a 회원 writing (it appears at the top), "더 보기", author-only controls, edit showing "(수정됨)", and delete with confirmation
