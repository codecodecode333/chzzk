# 06: 송출 화면 다듬기

**What to build:** The 송출 screen is ready for real phones:
- **Camera switch.** The 송출자 can switch between front and back cameras, both before and while live, without ending the 방송.
- **Video format.** Video targets 720p at 30fps where the device and network allow. The H.264 codec is preferred so iPhone Safari 시청자 can decode it, falling back to the browser default when H.264 isn't available.
- **Title validation.** The title follows the VOD title rule (trimmed, 1–100 chars) with inline errors before starting.
- **Clear failure messages.** Camera or microphone permission denied explains how to allow it. An insecure context (not HTTPS) or an unsupported browser explains why it can't broadcast. Start is disabled in these cases.

**Blocked by:** 02 (휴대폰으로 방송 시작 → 링크로 시청)

**Status:** resolved

- [x] A front/back camera switch is available on the preview and while live; switching while live replaces the outgoing video track without renegotiating a new 방송
- [x] The outgoing video requests 1280×720 at 30fps (as ideal constraints)
- [x] The publisher puts H.264 first via codec preferences when the browser supports it, and otherwise falls back silently
- [x] Inline title validation reuses the same rule and whitespace definition as the VOD form; start is blocked while invalid; the server still returns `400` for bad titles
- [x] Distinct messages for permission denied, no secure context, and missing WebRTC or `getUserMedia` support; start is disabled in each case
- [x] Frontend seam tests (media module mocked): camera switch calls the module while live; title validation messages and the blocked start; each failure message
- [x] An HTTP API seam test covers start with an invalid title → `400` (if not already covered)
