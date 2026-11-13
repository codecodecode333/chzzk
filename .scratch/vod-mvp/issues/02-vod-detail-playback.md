# 02: 상세 화면에서 VOD 재생

**What to build:** Clicking a VOD card opens `/vods/:id`. The page shows the title, description and registration date, and plays the 영상 파일 in a native `<video controls>` player. Playback starts without downloading the whole file, and the viewer can seek anywhere. This uses progressive MP4 over HTTP Range, with no HLS (see ADR-0001). An unknown id shows a not-found view. A video that fails to play shows "다시보기를 재생할 수 없습니다" in place of the player.

**Blocked by:** 01 (VOD 등록 후 목록에 보이기)

**Status:** ready-for-agent

- [ ] `GET /api/vods/{id}` returns `id`, `title`, `description`, `createdAt` and `streamUrl`; `streamUrl` is computed by the server, not stored
- [ ] `GET /api/vods/{id}` returns `404` (Problem Details) for an unknown id
- [ ] `GET /api/vods/{id}/stream` returns `206 Partial Content` with a correct `Content-Range` and body slice for range requests
- [ ] The stream returns `200` with the full body when no Range header is sent, always with `Accept-Ranges: bytes` and `Content-Type: video/mp4`
- [ ] The stream returns `404` when the VOD is unknown or its stored 영상 파일 is missing
- [ ] List cards link to `/vods/:id`; the detail URL is stable and shareable
- [ ] The detail page plays and seeks the video in the browser (verified manually with a real MP4)
- [ ] An unknown id renders the not-found view, and the `<video>` error event renders the playback-error message
- [ ] HTTP API seam tests cover: the detail response, detail `404`, stream `200`, stream `206` with byte-exact slices, and stream `404` when the file was removed from storage
