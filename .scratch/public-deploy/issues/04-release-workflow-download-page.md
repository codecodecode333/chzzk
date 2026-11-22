# 04: 릴리스 워크플로와 다운로드 화면

**What to build:** Pushing a `v*` tag builds the 데스크톱 앱 in GitHub Actions (`windows-latest` → x64 NSIS installer, `macos-latest` → universal dmg, unsigned) and publishes both to the GitHub Release with fixed names. The web frontend gains a `/download` screen with an OS card each: link via `releases/latest/download/<name>`, supported architectures, version, install and run steps, and how to get past SmartScreen/Gatekeeper; the top menu links "데스크톱 앱" to it. See spec and ADR-0006.

**Blocked by:** 03

**Status:** ready-for-agent

- [ ] Release workflow on `v*` tags builds both targets and attaches them to the Release with fixed names
- [ ] A first tag (`v0.1.0`) is pushed after the operator approves it, and the Release serves both files from `releases/latest/download/…`
- [ ] `/download` screen and top-menu link; frontend tests cover links, architectures and steps
