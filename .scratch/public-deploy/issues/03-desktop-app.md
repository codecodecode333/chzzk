# 03: 데스크톱 앱 (방송 목록과 시청)

**What to build:** An Electron 데스크톱 앱 (React + TypeScript renderer bundled in the app, served from a custom protocol origin) that shows the 방송 list from the configured backend — 방송 중 first, 종료 shown as such, refreshing on its own — and opens a 방송 중 방송 to watch it via WHEP, with clear messages when the backend is unreachable or the 방송 ends. Backend address defaults to `https://live.codecodecode333.dev`, overridable by env/setting. A remote-debugging switch exists for automated checks. No login, 댓글, 후원 or VOD. UI text uses GLOSSARY terms. See spec and ADR-0006.

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] New package with its own build; renderer tests in the frontend's style (Vitest + Testing Library + MSW): ordering, 종료 rendering, auto refresh, unreachable message, watch starts playback for a live 방송 via a faked media module, ended message
- [ ] Runs locally against a local dev backend with a live test 방송 and plays it (CORS from 01 works)
- [ ] electron-builder config produces Windows x64 NSIS and macOS universal dmg targets with fixed artifact names (`chzzk-desktop-setup-x64.exe`, `chzzk-desktop-mac-universal.dmg`); the Windows build is produced locally once
- [ ] README section for the 데스크톱 앱 (dev run, build, config)
