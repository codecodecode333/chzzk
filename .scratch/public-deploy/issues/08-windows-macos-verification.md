# 08: Windows·macOS 실제 확인과 README 기록

**What to build:** With a test 방송 on air on the public site (script from 05), download the 데스크톱 앱 from the public links. On this PC (Windows 10 x64): silent install, launch with remote debugging, confirm the 방송 list and playback, capture a screenshot. On a GitHub Actions `macos-latest` (Apple Silicon) runner: download the dmg from the public link, mount, launch, confirm list and playback, capture as an artifact. Tell the operator before installing on this PC. README records the tested OS/architecture and results, labelling macOS honestly as a CI runner and Intel Mac as build-only, plus external checks of the download links.

**Blocked by:** 04, 05, 07

**Status:** ready-for-agent

- [ ] Windows check done on this PC with a capture
- [ ] macOS CI check workflow run green with a capture artifact
- [ ] README results table (OS, arch, method, list, playback, capture)
