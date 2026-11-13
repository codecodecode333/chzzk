# 03: 등록 검증과 실패 처리

**What to build:** Bad input is rejected before upload in the form and again by the server, which is authoritative. The rules:
- title: trimmed, 1–100 chars, not whitespace-only
- description: optional, ≤ 2,000 chars
- file: `.mp4` extension, an `ftyp` signature in its first bytes, and ≤ 1GB

The server answers with RFC 7807 Problem Details: `400` for validation, `413` for oversize, `415` for non-MP4. When registration fails, the form shows the error and keeps the title and description the user typed. If the DB insert fails after the file was stored, the stored file is deleted, so no VOD in the list is ever unplayable and no orphaned 영상 파일 is left behind. Duplicate titles are allowed.

**Blocked by:** 01 (VOD 등록 후 목록에 보이기)

**Status:** ready-for-agent

- [ ] The server rejects an empty, whitespace-only or over-100-char title with `400` Problem Details naming the field
- [ ] The server rejects an over-2,000-char description with `400`
- [ ] The server rejects a wrong extension, and a `.mp4` without an `ftyp` signature (spoofed), with `415`
- [ ] The server rejects a file over the configured max size (1GB by default) with `413`; the limit is configurable
- [ ] Every API error, here and in the other endpoints, uses one Problem Details shape
- [ ] A forced DB-insert failure leaves no file in storage (compensation)
- [ ] Two VODs with the same title can both be registered
- [ ] The form shows inline errors for the title, description and file type/size before submitting, with submit blocked while it is invalid
- [ ] A server-side failure shows an error message in the form and preserves the entered title and description
- [ ] HTTP API seam tests cover each rejection, plus compensation; the size limit is tested with a small configured limit, not a real 1GB upload
- [ ] Frontend seam tests cover the validation messages and the blocked submit
