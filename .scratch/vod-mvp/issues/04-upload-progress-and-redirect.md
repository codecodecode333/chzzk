# 04: 업로드 진행률과 등록 후 이동

**What to build:** While a large 영상 파일 uploads, the registration form shows a progress bar and disables the submit button, so the user knows it's working and can't register twice. When registration succeeds, the user lands on the new VOD's detail page (`/vods/:id`) instead of the list and can immediately confirm it plays. Upload cancel is out of scope.

**Blocked by:** 01 (VOD 등록 후 목록에 보이기), 02 (상세 화면에서 VOD 재생)

**Status:** ready-for-agent

- [ ] The upload goes through a mechanism that reports progress (XHR/axios upload progress), and a progress bar reflects the percentage sent
- [ ] Submit is disabled from the start of the upload until it finishes or fails
- [ ] On `201`, the app navigates to the new VOD's detail page, using the id from the response
- [ ] On failure, the progress bar resets, submit re-enables, and the inputs are kept
- [ ] Frontend seam test: with a mocked upload that emits progress, the bar updates, submit is disabled during the upload, and success navigates to the detail route
