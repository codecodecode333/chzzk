# 스펙: OCI 공개 배포와 데스크톱 앱

**Status:** ready-for-agent

## Problem Statement

치지직은 지금 개발자 PC(개발 모드나 로컬 kind 클러스터)에서만 돌아서, 같은 와이파이 밖의 누구도 접속할 수 없다. 공개 주소도, 정식 HTTPS 인증서도 없어 휴대폰 카메라 송출과 시청에 자체 서명 경고가 따라온다. 웹 브라우저 말고는 방송을 볼 방법이 없고, PC에 설치해 쓰는 프로그램도 없다.

## Solution

구매한 도메인으로 누구나 `https://live.codecodecode333.dev`에 접속해 기존 기능(회원, VOD, 방송, 댓글, 후원)을 그대로 쓴다. 서비스는 OCI Always Free VM 한 대(우선 A1.Flex 2 OCPU·12GB, Ubuntu 24.04 aarch64)에서 Docker Compose로 돌고, Caddy가 Let's Encrypt 인증서로 HTTPS를 제공한다. Windows와 macOS용 **데스크톱 앱**(Electron, 로그인 없는 시청 전용)을 GitHub Releases에서 내려받아 설치하면 배포된 백엔드의 방송 목록을 보고 방송 중인 방송을 시청한다. 웹의 `/download` 화면이 OS별 파일 링크, 지원 아키텍처, 설치·실행 방법을 안내한다. 결과는 외부 네트워크에서 직접 확인하고 README에 남긴다. (ADR-0006)

## User Stories

1. As a 시청자, I want to open `https://live.codecodecode333.dev` from anywhere, so that I can use 치지직 without being on the developer's network.
2. As a 시청자, I want a valid HTTPS certificate with no browser warning, so that I trust the site.
3. As a 시청자, I want plain HTTP to redirect to HTTPS, so that I never use the site unencrypted.
4. As a 회원, I want signup, login and logout to work on the public site, so that I can use member features.
5. As a 회원, I want my login cookie to be HTTPS-only on the public site, so that it can't leak over HTTP.
6. As a 소유자, I want to register a VOD on the public site and have it play, so that 다시보기 works in production.
7. As a 소유자, I want to start a 방송 from my phone over the public site with a trusted certificate, so that the camera works without warnings.
8. As a 시청자, I want to watch a 방송 on the public site even from a network that blocks UDP, so that 라이브 works on restrictive Wi-Fi.
9. As a 회원, I want comments to work on the public site, so that I can talk about VODs and 방송.
10. As a 후원자, I want 후원 to work in Toss test mode on the public site, with success/fail returning to the public domain, so that the flow completes.
11. As an 운영자, I want a public health path that says only UP or DOWN and includes the database, so that I can monitor the service without leaking details.
12. As an 운영자, I want the backend's internal paths unreachable from the internet, so that the MediaMTX auth callback and webhook-only paths aren't exposed beyond what's intended.
13. As an 운영자, I want the service address and all secrets supplied by environment settings on the VM and never committed, so that the public repo leaks nothing.
14. As an 운영자, I want production settings in a separate profile, so that development defaults stay unchanged.
15. As an 운영자, I want one script to deploy or update the VM from my PC, so that releases are repeatable.
16. As an 운영자, I want the VM to rebuild images itself from the repo, so that ARM64 images need no registry or cross-build.
17. As an 운영자, I want the services to restart on their own after a crash or VM reboot, so that the site stays up.
18. As an 운영자, I want the VM firewall and OCI security list to open only 80/443, the WebRTC media port (UDP and TCP), and SSH from my own IP, so that the attack surface is small.
19. As an 운영자, I want a documented check of the home region's A1 availability and remaining Always Free limits before creating the VM, so that the choice is justified.
20. As an 운영자, I want a documented fallback order (other availability domains, smaller A1, retry script, E2.1.Micro last) and the reason for what was chosen, so that capacity limits are handled honestly.
21. As a 리뷰어, I want the README to state whether the domain is registered, its first-registration and renewal costs, the public address, and how DNS and TLS are set up, so that the deployment is auditable.
22. As a 시청자, I want a 데스크톱 앱 for Windows, so that I can watch 방송 without a browser.
23. As a 시청자, I want a 데스크톱 앱 for macOS on both Apple Silicon and Intel, so that any Mac can run it.
24. As a 시청자, I want the 데스크톱 앱 to show the 방송 list from the public backend with 방송 중 first, so that I see what's live.
25. As a 시청자, I want ended 방송 shown as 종료 and not playable, so that I'm not confused by a dead stream.
26. As a 시청자, I want to open a 방송 중 방송 in the 데스크톱 앱 and see and hear it, so that I can watch it.
27. As a 시청자, I want the 데스크톱 앱 to tell me clearly when the backend can't be reached or a 방송 has ended while I watch, so that I know what's going on.
28. As a 시청자, I want the 데스크톱 앱's 방송 list to refresh by itself, so that new 방송 appear.
29. As a 개발자, I want the 데스크톱 앱's backend address to default to the public domain but be overridable, so that I can point it at a local backend.
30. As an 운영자, I want the backend and MediaMTX to accept cross-origin requests only from the 데스크톱 앱's own origin and only for public reads and playback, so that other sites can't use them.
31. As a 시청자, I want a `/download` page on the site with links for each OS, the supported architectures, the version, and install and run steps, so that I know which file to get.
32. As a 시청자, I want the download page to explain how to get past SmartScreen and Gatekeeper warnings for the unsigned app, so that I can install it.
33. As a 시청자, I want a "데스크톱 앱" link in the site's top menu, so that I can find the download page.
34. As an 운영자, I want pushing a version tag to build the Windows installer and the macOS dmg in CI and publish them to a GitHub Release with fixed file names, so that the download links never change.
35. As an 운영자, I want a test-broadcast script that logs in a test 회원, starts a 방송 and 송출s fake camera video from a headless browser, so that playback can be verified without a phone.
36. As a 리뷰어, I want the README to record the Windows check (this PC, Windows 10 x64): download from the public link, install, list, playback, with a capture.
37. As a 리뷰어, I want the README to record the macOS check on a GitHub Actions Apple Silicon runner: download from the public link, run, list, playback, with a capture, stated honestly as a CI runner and not a real device.
38. As a 리뷰어, I want the README to record external checks of the domain, HTTPS and the download links.

## Implementation Decisions

- **Domain:** `codecodecode333.dev`, bought on Cloudflare Registrar (Active; first registration $8.20 (card charge 11,287원), renewal $12.20/yr, expires 2027-11-23, auto-renew on). The apex is shared with future course projects as subdomains, so this service lives at **`https://live.codecodecode333.dev`**: Caddy's site address, the 데스크톱 앱's default backend address, Toss redirects and the download page all use it. The public host is one environment value so the service can move to another domain later. DNS: Cloudflare A record `live` → the VM's public IP, DNS only (grey cloud), set after the VM exists. `.dev` is HSTS-preloaded, so browsers only ever use HTTPS; Let's Encrypt's HTTP-01 challenge on port 80 still works.
- **Production stack (one VM):** Docker Compose with Caddy, backend, frontend static files, PostgreSQL and MediaMTX, built on the VM from a checkout of the repo. Restart policies so services come back after crashes and reboots. Data (DB, 영상 파일) in named Docker volumes on the VM's free boot/block volume. The local dev compose and the kind cluster mode stay as they are.
- **Caddy:** automatic Let's Encrypt certificate for the domain (from the VM's `.env`), HTTP→HTTPS redirect. Routes: `/api/*` → backend (with large upload limits and long timeouts for VOD upload/playback); `/live/*` → MediaMTX WHEP/WHIP with the same rewrites as the Vite/nginx proxies (strip `/live`, keep `Location` under `/live`, drop `WWW-Authenticate`); `/healthz` → backend overall health; `/internal/*` refused; everything else → the SPA with `index.html` fallback. The Toss webhook path stays reachable as before.
- **Backend `prod` profile:** session cookie `Secure`; trust the proxy's forwarded headers; public base URL from env used for Toss success/fail redirects; DB and secrets only from env; no dev-only defaults for secrets (missing secret → fail to start, except Toss keys which keep the test-key rule from ADR-0004).
- **Health:** overall health (including the database) exposed publicly with status only, no details. Existing liveness/readiness probes unchanged.
- **CORS:** allowed only for the 데스크톱 앱's origin (a custom app protocol origin), only `GET` on the public 방송 read endpoints. MediaMTX allows that origin for WHEP playback. No credentials/cookies.
- **MediaMTX in production:** WebRTC on UDP 8189 and TCP 8189, additional host = the VM's public IP; auth callback to the backend with the shared secret, as in dev.
- **Firewall:** OCI security list and the Ubuntu image's iptables both open 80/tcp, 443/tcp, 8189/udp, 8189/tcp; 22/tcp only from the operator's IP.
- **OCI provisioning:** the operator creates the account and an API key in `~/.oci/config`; OCI CLI is installed via the official installer. Read-only checks first (home region, A1 limits and usage, availability domains); the VM is created only after the operator agrees. Fallback order: A1 2 OCPU/12GB in each AD → A1 1 OCPU/6GB → retry script → E2.1.Micro only as a last resort. Scripts are committed; OCIDs and keys are not.
- **Deploy script:** run from the operator's PC; SSH to the VM, update the checkout, `docker compose up --build -d`, wait for `/healthz`. The VM's `.env` (domain, DB password, MediaMTX secret, Toss test keys, LAN/public IP) is created once on the VM with mode 600.
- **데스크톱 앱 (new package):** Electron + electron-builder, React + TypeScript renderer bundled in the app and served from a custom protocol origin. Screens: 방송 list (방송 중 first, 종료 shown, auto refresh) and 방송 watch (WHEP playback, ended/unreachable messages). Reuses the web frontend's media playback approach and API shapes; no login, comments, 후원 or VOD. Backend address: build-time default to the public domain, overridable by setting/env. No auto-update. A remote-debugging switch for automated checks.
- **Packaging & release:** GitHub Actions workflow on `v*` tags: `windows-latest` builds the x64 NSIS installer, `macos-latest` builds the universal (arm64+x64) dmg; both are uploaded to the GitHub Release with fixed names (`chzzk-desktop-setup-x64.exe`, `chzzk-desktop-mac-universal.dmg`). Unsigned.
- **Download page:** a `/download` route in the web frontend with OS cards (file link via `releases/latest/download/<name>`, architectures, version, install/run steps, SmartScreen/Gatekeeper instructions) and a "데스크톱 앱" link in the top menu.
- **Verification tooling:** a test-broadcast script (headless Edge/Chrome with fake media; logs in a test 회원, starts a 방송, 송출s via WHIP); a Windows check (download from the public link, silent install, launch with remote debugging, read the list and playback state, capture); a macOS CI workflow (download from the public link, mount, launch, same checks, capture as artifact).

## Testing Decisions

- Good tests check external behaviour at the highest seam; no tests of internals or framework wiring.
- **Backend HTTP API seam** (prior art: existing Testcontainers `ApiSession`/MockMvc tests): public health returns status only and reflects the database; CORS preflight and `GET` on 방송 reads succeed for the 데스크톱 앱 origin and are refused for other origins and for other endpoints/methods; with the `prod` profile, the login session cookie is `Secure` and Toss redirect URLs use the configured public base URL.
- **데스크톱 앱 renderer seam** (prior art: frontend Vitest + Testing Library + MSW, `broadcastListPage.test.tsx`, `broadcastPages.test.tsx`): list ordering and 종료 rendering, auto refresh, unreachable backend message, watch screen starts playback for a live 방송 via the media module (faked) and shows the ended message.
- **Web frontend seam** (same prior art): `/download` shows both OS cards with correct links, architectures and steps; the top menu links to it.
- **Deployed system seam** (manual/CI, not unit tests): external curl checks of domain/HTTPS/redirect/health/download links; Windows check on this PC; macOS check on the CI runner; results and captures recorded in the README.

## Out of Scope

- Code signing and notarization, auto-update, Linux builds, Windows ARM64.
- 데스크톱 앱 login, comments, 후원, VOD.
- Multiple VMs, autoscaling in production, backups, monitoring/alerting stacks, CDN.
- Real-device macOS verification (no Mac available); real-phone 송출 on the public site (ready-for-human).
- Live (non-test) Toss payments.

## Further Notes

- Domain bought (see Implementation Decisions). README records the first registration as "$8.20 (카드 결제 11,287원)" and renewal as $12.20/yr.
- Needs from the operator: add the `live` A record once the VM's IP is known; create the OCI account and put an API key in `~/.oci/config` (never in chat). Work not depending on these proceeds first.
- ADR-0006 records the decisions; GLOSSARY gained **데스크톱 앱**.
