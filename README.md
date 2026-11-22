# 치지직 (Chzzk)

회원가입·로그인하고, VOD(화면 표기 "다시보기")를 등록·시청하고, 휴대폰으로 라이브 방송을 송출·시청하는 MVP. 용어는 `GLOSSARY.md`, 설계 결정은 `docs/adr/` 참고.

## Layout

| Path | What |
| --- | --- |
| `backend/` | Spring Boot 3 (Java 21, Gradle Kotlin DSL), Flyway, PostgreSQL |
| `frontend/` | React + Vite + TypeScript + Tailwind CSS |
| `desktop/` | 데스크톱 앱: Electron + electron-builder, React + Vite + TypeScript renderer (see 데스크톱 앱) |
| `compose.yaml` | Local PostgreSQL and MediaMTX (backend and frontend run natively) |
| `deploy/` | Cluster mode: kind config, Kubernetes manifests, up/down scripts (see 클러스터 모드) |
| `load/` | 클러스터 모드 부하(k6)와 유지 확인 스크립트, 실제 실행 기록 `load/results/` |
| `scripts/test-broadcast.mjs` | 테스트 방송: headless browser with a fake camera puts a 방송 on air (see 라이브) |
| `mediamtx/mediamtx.yml` | MediaMTX config: WebRTC only, auth via the backend's internal callback (ADR-0002) |

## Prerequisites

- Docker (for PostgreSQL and for the backend tests, which use Testcontainers)
- Any JDK 17+ to launch Gradle; the Gradle toolchain downloads JDK 21 automatically
- Node.js 24+

## Run locally

```sh
docker compose up -d                 # PostgreSQL on localhost:5432 (db/user/password: chzzk) + MediaMTX (see 라이브 below)

cd backend && ./gradlew bootRun      # http://localhost:8080
# Uploaded 영상 파일 are stored under backend/data/ (git-ignored).
# Override with VOD_STORAGE_ROOT, VOD_MAX_FILE_SIZE (default 1GB), DB_URL, DB_USERNAME, DB_PASSWORD.

cd frontend && npm install && npm run dev   # https://localhost:5173 (self-signed), proxies /api to :8080 and /live to MediaMTX
```

영상 파일 size limit: set `VOD_MAX_FILE_SIZE` (backend, e.g. `512MB`; the multipart request limit is derived from it)
and `VITE_VOD_MAX_FILE_SIZE` (frontend, same format, default `1GB`, read at `npm run dev`/`npm run build` time) to the
same value. The form checks the size first; the server stays authoritative and answers `413` with the configured limit.

### Backend container image

```sh
docker build -t chzzk-backend backend   # multi-stage: JDK 21 build, JRE 21 runtime, listens on 8080
```

Configured only by environment variables: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `VOD_STORAGE_ROOT` (image default
`/var/lib/chzzk/vod`, writable by uid/gid 10001), `VOD_MAX_FILE_SIZE`, `MEDIAMTX_API_URL`, `MEDIAMTX_AUTH_SECRET`,
`TOSS_CLIENT_KEY`, `TOSS_SECRET_KEY`. Built for running as one of many instances (ADR-0005):

- Health probes, open without login: `/actuator/health/liveness` and `/actuator/health/readiness`. Nothing else under
  `/actuator` is reachable (`404`).
- Every response carries `X-Served-By`: the instance name (`HOSTNAME`, the pod name in Kubernetes).
- Graceful shutdown: in-flight requests get up to 20s to finish (`spring.lifecycle.timeout-per-shutdown-phase`).
- Public overall health, open without login: `/healthz` (on the main port) answers only `{"status":"UP"}` (`200`) or
  `{"status":"DOWN"}` (`503`), with every health contributor, the database included, and no details. Liveness and
  readiness stay as above.

### `prod` profile (공개 배포)

For running behind the public HTTPS proxy (Caddy, ADR-0006), start the backend with `SPRING_PROFILES_ACTIVE=prod`
(`backend/src/main/resources/application-prod.yml`). Dev runs without it and keeps every default above. In `prod`:

- The login `SESSION` cookie is `Secure` (HTTPS only), still HttpOnly and SameSite=Lax.
- `X-Forwarded-Proto/-For/-Host` are trusted (`server.forward-headers-strategy=native`; Tomcat accepts them only from
  private and loopback addresses, i.e. the proxy on the Compose network), so a request that reached Caddy over HTTPS
  counts as secure (the `XSRF-TOKEN` cookie becomes `Secure` too).
- Toss success/fail redirects are absolute URLs on `PUBLIC_BASE_URL` (in dev they are paths on the page's origin).
- Required settings come only from the environment, with no defaults. The server refuses to start, naming the variable,
  when any is missing or blank:

| Variable | Required in `prod` | What |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | `prod` | Turns the profile on |
| `PUBLIC_BASE_URL` | yes, `https://…` | The public site address without trailing slash, e.g. `https://live.codecodecode333.dev` |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | yes | PostgreSQL, e.g. `jdbc:postgresql://postgres:5432/chzzk` |
| `MEDIAMTX_AUTH_SECRET` | yes | Shared secret of MediaMTX's auth callback (the same value MediaMTX gets) |
| `MEDIAMTX_API_URL` | no (`http://localhost:9997`) | MediaMTX control API, e.g. `http://mediamtx:9997` in Compose |
| `TOSS_CLIENT_KEY`, `TOSS_SECRET_KEY` | no | Your Toss **test** keys; ADR-0004's rule still refuses non-`test_` keys (see 후원) |
| `DESKTOP_APP_ORIGIN` | no (`app://chzzk`) | The 데스크톱 앱's origin, the only one given CORS (below) |
| `VOD_STORAGE_ROOT`, `VOD_MAX_FILE_SIZE` | no | As above |

CORS (ADR-0006, dev and prod alike): only the 데스크톱 앱's origin (`DESKTOP_APP_ORIGIN`, default `app://chzzk`, the
app's custom protocol origin) may preflight and `GET` the public 방송 list `/api/broadcasts` and detail
`/api/broadcasts/{id}`, without credentials (`Access-Control-Allow-Origin: app://chzzk`, no
`Access-Control-Allow-Credentials`). Every other origin, endpoint and method gets no CORS headers; a non-`GET` from
the app's origin to those two paths is refused (`403`). If the 데스크톱 앱 changes its scheme or host, set
`DESKTOP_APP_ORIGIN` to match.

## 클러스터 모드 (자동 확장)

The whole service on a local single-node [kind](https://kind.sigs.k8s.io/) cluster (ADR-0005): PostgreSQL ×1,
MediaMTX ×1, the backend at 2–5 pods under an HPA (CPU 50% of a 500m request, scale-in after 60s), and nginx as the
HTTPS entry point. The dev flow above is unchanged; everything for this mode is in `deploy/`.

Needs Docker running (Docker Desktop: give it ≥ 6 CPUs / 8 GB) and `kubectl` (Docker Desktop ships one). Run the
scripts from **Git Bash** (or any POSIX shell):

```sh
deploy/up.sh     # create cluster "chzzk", build + side-load images, Secret from .env, apply, wait until ready
                 # re-run after code changes: rebuilds the images and rolls the pods, keeps data
deploy/down.sh   # delete the cluster (database and 영상 파일 included)
```

- **kind** itself: `up.sh` runs `deploy/fetch-kind.sh` on first use, which downloads the pinned release from
  github.com/kubernetes-sigs/kind and checks it against the release's SHA256 into `deploy/.tools/` (git-ignored).
  Or `winget install Kubernetes.kind`; a `kind` on `PATH` is used when `deploy/.tools/` has none.
- **Open** https://localhost:8443 (self-signed certificate, generated per nginx pod). Host port 8443 (TCP) and 8189
  (UDP, WebRTC media) are published on all interfaces like the dev setup, so a phone on the same Wi-Fi can use
  `https://<LAN_IP>:8443`; the Kubernetes API listens on 127.0.0.1 only.
- **Secrets**: `up.sh` builds the Secret `chzzk-env` from the repo-root `.env` (`MEDIAMTX_AUTH_SECRET`, `LAN_IP`,
  `TOSS_CLIENT_KEY`/`TOSS_SECRET_KEY` if set). Without `.env`, `LAN_IP` is `127.0.0.1` and a random
  `MEDIAMTX_AUTH_SECRET` is generated once and kept in the cluster. Nothing secret is in the repo. The in-cluster
  PostgreSQL uses the local-only `chzzk`/`chzzk` credentials.
- **Look around** (context `kind-chzzk`, namespace `chzzk`):
  ```sh
  kubectl --context kind-chzzk -n chzzk get hpa backend          # CPU reading / target, replica count
  kubectl --context kind-chzzk -n chzzk get pods -l app=backend   # backend pods
  kubectl --context kind-chzzk -n chzzk logs deploy/backend       # one backend pod's log
  ```
  Every API response names the pod that served it in `X-Served-By`.

| File | What |
| --- | --- |
| `deploy/kind-cluster.yaml` | Single node; host 8443 → nginx (NodePort 30443), UDP 8189 → MediaMTX (NodePort 30189) |
| `deploy/k8s/10-postgres.yaml` | PostgreSQL StatefulSet + 2Gi PVC |
| `deploy/k8s/20-mediamtx.yaml` | MediaMTX (1 replica, config from `mediamtx/mediamtx.yml`); auth callback → backend Service with the secret |
| `deploy/k8s/30-backend.yaml` | Backend Deployment + Service + shared 10Gi 영상 파일 PVC (`/var/lib/chzzk/vod`, every pod mounts it) |
| `deploy/k8s/40-hpa.yaml` | HPA: CPU 50%, min 2, max 5, scale-down stabilization 60s |
| `deploy/k8s/50-frontend.yaml` | nginx entry point (`frontend/Dockerfile`) |
| `deploy/k8s/metrics-server/` | metrics-server v0.9.1 official manifest, plus `--kubelet-insecure-tls` for kind |

Backend pods: CPU request 500m / limit 1, memory 512Mi / 768Mi, readiness and liveness on the actuator probes,
preStop 5s + 20s graceful shutdown inside a 40s termination grace period. The image's entrypoint
(`backend/docker-entrypoint.sh`) reports the JVM's exit 143 after that SIGTERM as 0, so pods removed on scale-in end
`Completed`, not `Error`.

### 부하와 유지 확인

`deploy/up.sh`로 클러스터를 띄운 뒤, Git Bash 창 두 개(또는 셋)에서:

```sh
# 1) 확장 지켜보기 (선택)
kubectl --context kind-chzzk -n chzzk get hpa backend -w

# 2) 유지 확인: 회원·방송·댓글 3개·VOD를 직접 만들고, 로그인 세션 하나로 5초마다 한 줄씩 찍는다
DURATION=540 node load/verify.mjs      # DURATION 없으면 Ctrl+C까지; 끝나면 요약을 찍고 방송을 종료한다

# 3) 부하: grafana/k6:1.3.0 이미지로 실행 (설치 불필요). 30초 램프업 → 3분 유지 → 정지
load/run-k6.sh                          # VUS=60 RAMP=30s HOLD=3m 기본값, BASE_URL로 대상 변경
```

- `load/k6-public-reads.js`: 로그인 없는 공개 읽기(방송 목록, VOD 목록, 방송·VOD 댓글)를 섞어 보낸다. 대상은
  `BASE_URL`(기본 `https://host.docker.internal:8443`, 컨테이너에서 본 진입점), 자체 서명 인증서는 그대로 받는다.
- `load/verify.mjs`: Node만 있으면 된다(추가 패키지 없음). 시작할 때 작은 MP4(256KB, 스크립트가 생성)를 VOD로
  등록하고 등록을 처리한 파드를 적어 둔다. 한 줄 = 시각, `/api/me` 결과, 방송 수(방송 중 + 종료), 자기 방송의
  댓글 수, VOD 재생(매번 다른 4KB 구간을 Range로 받아 올린 바이트와 비교), 네 요청의 `X-Served-By`(파드 이름
  끝부분), 백엔드 파드 수(ready/전체, `kubectl`로 조회). 만든 방송은 미디어를 보내지 않으므로 1분쯤 뒤 서버가
  자동 종료한다(송출 감시). 그래서 방송 수는 방송 중과 종료를 합쳐 센다. 모든 줄이 첫 줄과 같고, 로그인이
  유지되고, VOD가 매번 재생되고 등록 파드가 아닌 파드에서도 한 번 이상 재생되면 종료 코드 0. 끝나면 방송을
  종료하고 VOD를 지운다.

### 실제 실행 결과 (2026-11-22, Docker Desktop 단일 노드)

전체 기록은 `load/results/`(`hpa-watch.txt`, `pods-watch.txt`, `verify.txt`, `k6-summary.txt`, `job-lease.txt`).
k6는 05:24:42에 시작해 05:28:19에 끝났다(VUS=60). 백엔드는 부하 시작 약 40초 뒤 2 → 5로 늘었고, 부하가 끝나고
약 1분 반 뒤(CPU가 내려간 뒤 축소 안정화 60초) 다시 2가 됐다. 축소로 내려간 파드 3개는 정상 종료(`Completed`,
종료 코드 0)로 끝났다.

`kubectl get hpa -w` (앞에 붙인 시각은 캡처 때 기록, 일부 줄 생략):

```text
05:24:39  backend   Deployment/backend   cpu: 11%/50%    2         5         2          55m
05:25:09  backend   Deployment/backend   cpu: 168%/50%   2         5         2          55m
05:25:25  backend   Deployment/backend   cpu: 199%/50%   2         5         5          55m
05:26:41  backend   Deployment/backend   cpu: 166%/50%   2         5         5          57m
05:28:27  backend   Deployment/backend   cpu: 118%/50%   2         5         5          58m
05:28:42  backend   Deployment/backend   cpu: 46%/50%    2         5         5          59m
05:29:12  backend   Deployment/backend   cpu: 2%/50%     2         5         5          59m
05:29:57  backend   Deployment/backend   cpu: 1%/50%     2         5         2          60m
```

`kubectl get pods -l app=backend -w` (일부 줄 생략):

```text
05:25:10  backend-7bdf575776-f9kll   0/1     Pending           0          0s
05:26:37  backend-7bdf575776-bwhkq   1/1     Running           0          88s
05:29:42  backend-7bdf575776-bwhkq   1/1     Terminating       0          4m33s
05:29:48  backend-7bdf575776-bwhkq   0/1     Completed         0          4m39s
05:29:48  backend-7bdf575776-mcbbl   0/1     Completed         0          4m39s
05:29:48  backend-7bdf575776-f9kll   0/1     Completed         0          4m39s
```

`node load/verify.mjs` (일부 줄 생략). VOD는 `7x598`에서 등록됐고, 새 파드가 ready가 되자마자(05:26:43)
`bwhkq`가 그 영상을 내려줬다. 새 파드 3개는 05:25:10에 생겼지만 JVM이 부하 속 같은 노드에서 뜨느라 05:26:37–49에야
ready가 되어 그때부터 요청을 받았다:

```text
seeded 회원 vmwquwcfi, 방송 10 (방송 중), 댓글 3개, VOD 6 (262144B, 등록: backend-7bdf575776-7x598) — https://localhost:8443
time      me                      방송(전체)  댓글  VOD  X-Served-By (me / 방송 / 댓글 / VOD)  pods(ready/all)
05:24:23  200 vmwquwcfi                   10     3   OK  5zz9h / 7x598 / 7x598 / 7x598         2/2
05:25:14  200 vmwquwcfi                   10     3   OK  7x598 / 7x598 / 7x598 / 5zz9h         2/5
05:26:43  200 vmwquwcfi                   10     3   OK  7x598 / 5zz9h / 5zz9h / bwhkq         3/5
05:26:49  200 vmwquwcfi                   10     3   OK  5zz9h / 7x598 / 7x598 / 5zz9h         5/5
05:26:57  200 vmwquwcfi                   10     3   OK  mcbbl / 7x598 / bwhkq / f9kll         5/5
05:27:03  200 vmwquwcfi                   10     3   OK  f9kll / 5zz9h / mcbbl / mcbbl         5/5
05:29:40  200 vmwquwcfi                   10     3   OK  f9kll / 7x598 / bwhkq / 5zz9h         5/5
05:29:45  200 vmwquwcfi                   10     3   OK  5zz9h / 5zz9h / 5zz9h / 5zz9h         2/2
05:31:00  200 vmwquwcfi                   10     3   OK  5zz9h / 7x598 / 5zz9h / 5zz9h         2/2

108 ticks: 로그인 유지 OK, 방송 수 그대로 OK (10), 댓글 수 그대로 OK (3), VOD 재생 OK
VOD 6: backend-7bdf575776-7x598에서 등록, 재생한 파드 5개 (등록 파드 외 4개): backend-7bdf575776-7x598, backend-7bdf575776-5zz9h, backend-7bdf575776-bwhkq, backend-7bdf575776-f9kll, backend-7bdf575776-mcbbl
X-Served-By 5개: backend-7bdf575776-5zz9h, backend-7bdf575776-7x598, backend-7bdf575776-bwhkq, backend-7bdf575776-mcbbl, backend-7bdf575776-f9kll
pods seen: 2/2, 2/5, 3/5, 4/5, 5/5
```

k6: 요청 54,343건(약 253/s), 실패 0%, 응답 시간 중앙값 376ms · p95 1.63s. 파드가 늘고 줄어드는 동안에도 로그인,
방송 수, 댓글 수가 그대로였고, 한 파드에서 등록한 VOD를 다른 네 파드가 그대로 재생했으며, 요청은 5개 인스턴스가
나눠 처리했다. 같은 부하 중 파드 5개일 때 `job_lease`를 2초마다 본 결과(`job-lease.txt`), 송출 감시는 10초에 한 번,
후원 만료는 1분에 한 번만 시작됐다(파드 수와 무관하게 주기당 한 번).

### 한계와 조정

- **PostgreSQL과 MediaMTX는 늘리지 않는다.** 상태(세션·VOD·방송·댓글·후원)를 한 곳에 모아 두는 것이 백엔드를
  자유롭게 늘리고 줄일 수 있는 이유다. PostgreSQL은 복제 구성 없이 1개, MediaMTX는 송출·시청 연결을 직접 쥐고
  있는 중계기라 여러 개로 나누면 같은 방송의 송출과 시청이 다른 인스턴스로 갈 수 있어 1개로 둔다(ADR-0005).
- **단일 노드 공유 볼륨.** 영상 파일 볼륨(`ReadWriteOnce`, kind의 로컬 경로)은 모든 파드가 같은 노드에 있을 때만
  함께 쓸 수 있다. 노드를 늘리려면 영상 파일을 오브젝트 스토리지 같은 곳으로 옮겨야 한다.
- **같은 노드의 CPU를 나눠 쓴다.** 새 파드도 같은 Docker Desktop CPU를 쓰므로, 부하 중에는 JVM 시작이 느려
  (위 실행에서 약 90초) 확장 효과가 늦게 나타난다. Docker Desktop에 CPU를 넉넉히 주자.
- **CPU 요청·목표 조정.** HPA는 파드 CPU 사용량을 요청(`deploy/k8s/30-backend.yaml`의
  `resources.requests.cpu`, 500m) 대비 비율로 보고 목표(`deploy/k8s/40-hpa.yaml`의 `averageUtilization`, 50%)를
  넘으면 늘린다. 부하를 걸어도 늘지 않으면 먼저 `VUS`를 올리고, 그래도 안 되면 요청을 낮추거나(예: 250m) 목표를
  낮춘다. 너무 쉽게 늘면 반대로. 고친 뒤 `kubectl --context kind-chzzk apply -f deploy/k8s/`(또는
  `deploy/up.sh`)로 반영한다. 위 실행은 기본값(500m, 50%, VUS 60)으로 조정 없이 확장됐다. 축소 속도는
  `40-hpa.yaml`의 `scaleDown.stabilizationWindowSeconds`(60초).

## 라이브 on a phone (same Wi-Fi)

The phone publishes and watches with WebRTC through MediaMTX (ADR-0002). Camera access needs HTTPS, so Vite serves a
self-signed certificate on all interfaces and proxies `/api` (backend) and `/live` (WHIP/WHEP signaling to MediaMTX);
media flows directly between the device and MediaMTX over UDP 8189.

1. Copy `.env.example` to `.env` next to `compose.yaml` and fill it in. Both `docker compose` and the backend
   (`bootRun` from `backend/`) read it; re-run `docker compose up -d` and restart the backend after changing it.
   - `LAN_IP`: this PC's LAN IP (`ipconfig`, the Wi-Fi/Ethernet adapter's IPv4 address, e.g. `192.168.0.23`).
     MediaMTX advertises it as its WebRTC address; without it only a browser on this PC can connect (it defaults to
     `127.0.0.1`).
   - `MEDIAMTX_AUTH_SECRET`: a long random value (see `.env.example`). MediaMTX sends it to the backend's auth
     callback; while it is unset the backend refuses every publish and watch (and logs a warning at startup).
2. Allow inbound connections in the Windows firewall (PowerShell as administrator). Only the HTTPS page and WebRTC
   media are reachable from the LAN: MediaMTX's signaling (8889) and control API (9997) listen on loopback only and
   phones reach signaling through the Vite proxy, so the 송출 키 never crosses the network unencrypted.
   ```powershell
   New-NetFirewallRule -DisplayName "chzzk dev" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 5173
   New-NetFirewallRule -DisplayName "chzzk dev media" -Direction Inbound -Action Allow -Protocol UDP -LocalPort 8189
   ```
3. Start everything (`docker compose up -d`, backend, `npm run dev`), open `https://<PC IP>:5173` on the phone and
   accept the certificate warning. `/broadcasts/new` is the 송출 screen; the "시청 링크" it shows is the 시청 screen.

MediaMTX asks the backend's internal callback (`POST /internal/mediamtx/auth?secret=<MEDIAMTX_AUTH_SECRET>`, not
proxied) on every WHIP/WHEP request: a call without the shared secret is refused (the backend listens on every
interface, so anyone on the LAN could otherwise ask it), publishing needs the 방송's current 송출 키 as `Authorization: Bearer`,
and only a 방송 중 방송 can be published or watched. `compose.yaml` puts the secret into MediaMTX's callback address
(`MTX_AUTHHTTPADDRESS`), since MediaMTX can't add headers. The 송출 키 only authenticates
that media connection: only the logged-in 소유자 gets one (from 방송 시작, or a fresh one from "이어서 송출", which
replaces the old key and cuts its publisher), the 송출 screen keeps it in memory only, and 방송 종료 uses the login
session. The backend reads MediaMTX's control API on `localhost:9997` (override with `MEDIAMTX_API_URL`).
Every 10s the publishing monitor asks that API which 방송 are receiving media; a 방송 중 방송 with no media for
over 60s (or that never sent any) is ended automatically. While MediaMTX can't be asked, only 방송 that never sent
any media are ended (for the others, silence can't be told from the outage).

### 테스트 방송 (no phone)

`scripts/test-broadcast.mjs` puts a 방송 on air from a headless Chrome or Edge (whichever is installed) with a fake
camera and microphone (a test pattern and a beep). It logs a test 회원 in (signing up when the login is refused),
starts the 방송 through the 송출 screen itself (`/broadcasts/new`: title → 방송 시작, WHIP 송출), prints the 방송 id and
keeps 송출 until Ctrl+C or `--duration`; on exit it presses 방송 종료. Node 22+ only, no npm install: it drives the
browser over the DevTools protocol with Node's built-in WebSocket.

```sh
# Local dev stack (self-signed certificate: --insecure); a fresh throwaway 회원, until Ctrl+C
node scripts/test-broadcast.mjs --base-url https://localhost:5173 --insecure

# Public site, a fixed test 회원, 10 minutes, and check it as a logged-out 시청자 too
TEST_BROADCAST_PASSWORD=... node scripts/test-broadcast.mjs --base-url https://live.codecodecode333.dev \
  --email test-broadcast@example.com --duration 600 --watch
```

Options: `--email`/`--password` (or `TEST_BROADCAST_PASSWORD`)/`--nickname` for the 회원 (without `--email` a new
throwaway 회원 is signed up each run), `--title`, `--duration <s>`, `--watch` (a second, logged-out page checks that
the 방송 is in the 방송 중 list and that its video plays: `currentTime` advances), `--insecure` (accept a self-signed
certificate; never needed on the public site), `--browser <path>` (or `BROWSER`), `--headed`. The line
`BROADCAST_ID=<id>` is printed for other scripts. If this 회원 already has a 방송 중 방송, it is ended first
("새 방송으로 시작"). A dropped 송출 is retried with the screen's "다시 연결". Exit code 0 only if the 방송 started and
ended cleanly (and, with `--watch`, played). The browser runs in its own process group so Ctrl+C reaches only the
script, which ends the 방송 and then closes it; if the script is killed outright, the headless browser keeps 송출 until
you close it (the next run with the same `--email` ends that 방송 first), and the server auto-ends the 방송 about 70s
after media stops.

Verified 2026-11-23 against the local dev stack (Chrome and Edge, Windows 10): the 시청자 page found the 방송 in the
방송 중 list and played 1280x720 video (`currentTime` +3s over 3s); Ctrl+C and `--duration` both ended it via 방송 종료.

### Real-phone checklist

Real WebRTC through MediaMTX isn't run in the automated tests, so check it by hand on the same Wi-Fi before
calling 라이브 done (ticket 07). Use two phones and a desktop browser; "list" is the 라이브 home (`/`).

**A. Android Chrome as 송출자 → iPhone Safari and a desktop browser as 시청자**
- [ ] On Android Chrome open `/broadcasts/new`: it asks to log in first. Log in as the 송출자 (회원 A), allow camera
      and microphone, enter a title, press 방송 시작: the 방송 중 badge and the 방송 경과 시간 timer appear.
- [ ] On iPhone Safari log in as another 회원 (B) and open the 시청 링크: it shows A's 닉네임 and no 송출 or 종료
      controls; the desktop browser, logged out, shows the same.
- [ ] Open the 시청 링크 on iPhone Safari and on the desktop: video plays muted with sub-second delay (wave a hand,
      or show a stopwatch on the 송출 phone and compare).
- [ ] Press "소리 켜기" on each 시청자: sound plays.
- [ ] Switch the camera (전면 ↔ 후면) on the 송출 phone while live: the 시청자 picture switches without reconnecting
      and the 방송 keeps its link and timer.
- [ ] The list shows the 방송 under 방송 중 with the 방송 중 badge and A's 닉네임.
- [ ] Press 방송 종료 and confirm: the 송출 phone shows 종료됨 and its camera turns off; every 시청자 switches to
      "종료된 방송입니다"; the list shows it under 종료된 방송 with the 종료 badge and both its start and end time.
- [ ] Second 회원: on the iPhone (logged in as B) open A's 시청 링크 while 방송 중 and again after 종료: neither
      shows 송출 ("이어서 송출"), 제목 수정 or 삭제 controls, while A's phone shows 제목 수정 (and 삭제 once 종료).

**B. iPhone Safari as 송출자 → Android Chrome as 시청자**
- [ ] The same flow as A with the roles swapped (log in on the iPhone as the 송출자): start, sub-second playback,
      "소리 켜기", camera switch while live, 방송 종료 → "종료된 방송입니다", and the list's 방송 중 / 종료 badges.

**C. Interruptions** (either phone as 송출자, the other as 시청자)
- [ ] Open the 시청 링크 before pressing 방송 시작 on a just-started 방송, or while the 송출자 reconnects: the 시청자
      shows "송출 준비 중…" and starts playing on its own once media flows.
- [ ] Reload the 송출 page while live and press "이어서 송출" within 60s: the same 방송 continues (same 시청 링크, same
      start time) and the 시청자 recover on their own.
- [ ] Resume on a second device: while the first phone is live, log in as the same 회원 on the other phone (or a
      desktop browser), open `/broadcasts/new` and press "이어서 송출": the 방송 continues from the second device; the
      first one shows "송출이 끊겼습니다", and its "다시 연결" says another device took over (its old 송출 키 is
      refused). End the 방송 from either device.
- [ ] Turn Wi-Fi off and on again on the 송출 phone: it shows "송출이 끊겼습니다"; "다시 연결" continues the same 방송.
- [ ] Kill the 송출 tab (close it, or lock the phone): within about 70s the 방송 shows 종료 in the list and
      "종료된 방송입니다" for the 시청자.

## 데스크톱 앱 (`desktop/`)

A 시청-only Electron app (ADR-0006): the 방송 list (방송 중 first, recent 종료 shown as 종료 and not playable,
refreshed every 10s) and the 시청 screen (WHEP playback; "방송이 종료되었습니다" once the 5s status check or a dropped
connection finds the 방송 종료). While the backend can't be reached both screens say "서버에 연결할 수 없습니다" and keep
retrying. No login, 댓글, 후원 or VOD. The React renderer is bundled into the app and served from the custom protocol
origin **`app://chzzk`** (registered standard + secure, so fetch and WebRTC work as on an HTTPS page); every backend
call is a cross-origin `GET <backend>/api/broadcasts[/{id}]` and WHEP goes to `<backend>/live/.../whep`, both without
credentials. The backend (`DESKTOP_APP_ORIGIN`) and MediaMTX (`webrtcAllowOrigins`) allow only that origin.
`contextIsolation` on, `nodeIntegration` off, sandboxed preload; the renderer only sees `window.chzzk.backendUrl`.

```sh
cd desktop && npm ci
npm test            # renderer: Vitest + Testing Library + MSW (happy-dom), media module faked
npm run lint        # oxlint
npm start           # build the renderer, then run Electron against the configured backend
npm run dist:win    # release/chzzk-desktop-setup-x64.exe   (Windows x64 NSIS, per-user, unsigned; `/S` = silent)
npm run dist:mac    # release/chzzk-desktop-mac-universal.dmg (arm64 + x64, unsigned; must run on macOS)
```

Settings (first match wins):

| What | How |
| --- | --- |
| Backend address | `--backend-url=<url>` flag › `CHZZK_BACKEND_URL` › `"backendUrl"` in `settings.json` in the app's user-data folder (Windows `%APPDATA%\Chzzk\`, macOS `~/Library/Application Support/Chzzk/`) › default `https://live.codecodecode333.dev` |
| Remote debugging (automated checks) | `CHZZK_REMOTE_DEBUGGING_PORT=<port>` (or Chromium's own `--remote-debugging-port=<port>`); then `http://127.0.0.1:<port>/json/list` gives the page's DevTools WebSocket |

**Against the local dev stack.** The app needs one origin that serves both `/api` and `/live`, like Caddy in
production. The web frontend's Vite server can't be it: Vite's own CORS handling answers every preflight (the WHEP
`POST` has one) without allowing `app://chzzk`. `desktop/scripts/dev-backend.mjs` is a tiny plain-HTTP pass-through
on `:8090` (`/api` → backend `:8080`, `/live` → MediaMTX `127.0.0.1:8889` with the same rewrites as the other proxies);
`compose.yaml` lets MediaMTX allow `app://chzzk` (`MTX_WEBRTCALLOWORIGINS`).

```sh
# compose, backend (:8080) and frontend dev server running as in Run locally (.env with MEDIAMTX_AUTH_SECRET)
node scripts/test-broadcast.mjs --base-url https://localhost:5173 --insecure               # a live 테스트 방송
node desktop/scripts/dev-backend.mjs                                                        # http://localhost:8090
cd desktop && CHZZK_BACKEND_URL=http://localhost:8090 npm start
# or the packaged build: CHZZK_BACKEND_URL=http://localhost:8090 desktop/release/win-unpacked/Chzzk.exe
```

Checked 2026-11-23 on this PC (Windows 10 x64): the locally built `release/win-unpacked/Chzzk.exe` (same files as the
installer) with `CHZZK_REMOTE_DEBUGGING_PORT=9333`, read over DevTools: page origin `app://chzzk`, `require`
undefined in the renderer; the list showed the 테스트 방송 under 방송 중 and an empty 종료 list; opening it played
1280×720 audio+video (`readyState` 4, `currentTime` advancing); a second 테스트 방송 with `--duration 30` switched to
"방송이 종료되었습니다" when it ended; stopping the dev backend showed "서버에 연결할 수 없습니다 (http://localhost:8090)".

## 공개 범위 (access policy)

Who may do what, as the server enforces it (the pages only hide controls; the server stays the authority).
누구나 = anyone, logged in or not; 로그인 회원 = any logged-in 회원; 소유자 = the 회원 who registered the VOD or started
the 방송; 작성자 = the 회원 who wrote the 댓글.

Not logged in → `401`, logged in but not the 소유자/작성자 → `403`, a conflict with the current state → `409`, all as
Problem Details. Login is checked first, so a visitor gets `401` even for an unknown id; a 회원 gets `404` for an unknown
VOD, 방송 or 댓글. Writes (POST/PUT/PATCH/DELETE) also need the CSRF token (`403` without it); the frontend's HTTP
client sends it by itself.

| Action | Who | Otherwise |
| --- | --- | --- |
| 회원가입 (`POST /api/auth/signup`; logs the new 회원 in) | 누구나 | `409` naming the field for a taken email or 닉네임 |
| 로그인 (`POST /api/auth/login`) | 누구나 | `401` with one message for a wrong email or password |
| 로그아웃 (`POST /api/auth/logout`) | 누구나 (ends the session if there is one) | — |
| 내 정보 (`GET /api/me`) | 로그인 회원 | `401` |
| VOD 목록·상세 (`GET /api/vods`, `/api/vods/{id}`), 재생 (`GET /api/vods/{id}/stream`) | 누구나 | — |
| 방송 목록·상세 (`GET /api/broadcasts`, `/api/broadcasts/{id}`; `?ownerId=` narrows the 목록 to one 회원's 방송, still public data) | 누구나 | — |
| 방송 시청 (WHEP through MediaMTX) | 누구나, while 방송 중 | refused by the auth callback once 종료 |
| VOD 등록 (`POST /api/vods`) | 로그인 회원 (becomes its 소유자) | `401` |
| VOD 수정·삭제 (`PATCH`, `DELETE /api/vods/{id}`; 삭제 takes its 영상 파일 and 댓글) | 소유자 | `401` / `403` |
| 방송 시작 (`POST /api/broadcasts`) | 로그인 회원 (becomes its 소유자); one 방송 중 방송 per 회원 | `401`; `409` with `broadcastId` of the 방송 중 one |
| 방송 종료 (`POST /api/broadcasts/{id}/end`) | 소유자, by login session; ending twice is harmless | `401` / `403` |
| 이어서 송출 (`POST /api/broadcasts/{id}/publish-key`, a fresh 송출 키) | 소유자, while 방송 중 | `401` / `403`; `409` once 종료 |
| 송출 (WHIP through MediaMTX) | whoever holds the 방송's current 송출 키, while 방송 중 | refused by the auth callback |
| 방송 제목 수정 (`PATCH /api/broadcasts/{id}`) | 소유자, 방송 중 or 종료 | `401` / `403` |
| 방송 삭제 (`DELETE /api/broadcasts/{id}`, with its 댓글 and its FAILED 후원 orders) | 소유자, once 종료, and only without 후원 or orders that may still be paid | `401` / `403`; `409` while 방송 중; `409` "후원 내역이 있는 방송은 삭제할 수 없습니다" once it has a confirmed 후원; `409` "결제가 진행 중인 후원이 있어 지금은 삭제할 수 없습니다" while an order is PENDING or EXPIRED |
| 댓글 목록 (`GET /api/vods/{id}/comments`, `/api/broadcasts/{id}/comments`; `?before=` for older, `?after=` for newer) | 누구나 | `404` for an unknown VOD or 방송 |
| 댓글 쓰기 (`POST` to the same paths; on a 방송 중 or 종료 방송) | 로그인 회원 | `401`; `404` for an unknown VOD or 방송 |
| 댓글 수정·삭제 (`PATCH`, `DELETE /api/comments/{id}`) | 작성자 | `401` / `403` |
| 후원 내역·합계 (`GET /api/broadcasts/{id}/donations`, confirmed 후원 only, newest confirmed first; each entry's `seq` is its place in confirmation order and the cursor: `?before=` for older, `?after=` for newer; `total` counts confirmed 후원 only) | 누구나, 방송 중 or 종료 | `404` for an unknown 방송 |
| 후원 시작 (`POST /api/broadcasts/{id}/donations`, a PENDING 후원 order) | 로그인 회원 who is not the 소유자, while 방송 중 | `401`; `403` on your own 방송; `409` once 종료; `400` for the amount (1,000–1,000,000원 in 1,000원 steps) or a 메시지 over 100자; `404` for an unknown 방송 |
| 후원 확정 (`POST /api/donations/confirm`, from the success page; the server confirms with Toss) | that order's 후원자 | `401` / `403`; `404` for an unknown order; `400` with Toss's `code` when rejected, not paid (`ABORTED`, `EXPIRED`, `CANCELED`, ...) or the amount differs; `409` for a failed order; `503` when Toss can't be reached or the payment is still in progress there (the order stays open; retry) |
| 후원 실패 (`POST /api/donations/{orderId}/fail`, from the fail page) | that order's 후원자 | `401` / `403`; `404` for an unknown order |
| Toss payment webhook (`POST /internal/payments/webhook`, no session or CSRF) | anyone; the body is only a hint, re-checked by looking the payment up with Toss | always `200`, except `503` when Toss can't be reached |
| MediaMTX auth callback (`/internal/mediamtx/auth`) | MediaMTX with the shared secret | bare `401` |
| 헬스 체크 (`GET /actuator/health/liveness`, `/actuator/health/readiness`; Kubernetes probes. 클러스터 모드의 nginx는 `/actuator`를 넘기지 않으므로 클러스터 안에서만 닿는다) | 누구나 | 나머지 `/actuator/**`는 `404` |

모든 응답에는 그 요청을 처리한 인스턴스 이름(`HOSTNAME`, Kubernetes에서는 파드 이름)이 `X-Served-By` 헤더로 붙는다.

시청, 목록 and 댓글 목록 need no login: a VOD or 방송 link shared with someone should just play, and reading the 댓글
next to it reveals nothing that isn't already public (only the 작성자's 닉네임, never an email). Login is asked only
where a write has to be tied to a 회원: who owns a VOD or 방송, who wrote a 댓글.

Login and 회원가입 also issue a fresh CSRF token (a new `XSRF-TOKEN` cookie), so a token known before login stops
working; the frontend client reads the cookie on every write, so it picks the new one up by itself.

While a 방송 is 방송 중, its 시청 page polls for new 댓글 every 5s (with `?after=`, page by page, so none is skipped).
Polling brings new 댓글 only: other people's edits and deletions show after a reload.

Only a CONFIRMED order is a 후원. Deleting a 종료 방송 removes its FAILED orders with it (final, never 후원). A 방송
with any confirmed 후원 stays, because payment records must not disappear, and so does one with a PENDING or EXPIRED
order, because Toss may still approve that payment (an EXPIRED order is confirmed if Toss later shows it paid): such a
방송 can't be deleted. The `donation` FK is `ON DELETE RESTRICT` as a backstop.

### Running with login

Nothing extra beyond the steps above: Flyway creates the 회원 and session tables on backend start, and sessions live
in the same PostgreSQL (`.env` is only needed for 라이브 on a phone). Sign up at `/signup`; there are no seeded 회원.

### Upgrading an existing database

Migration `V4__vod_owner.sql` deletes every VOD row registered before 회원 existed (nobody can own them), and
`V6__broadcast_owner.sql` deletes every 방송 started before 회원 existed. Neither touches 영상 파일, so
the deleted VODs' files stay on disk under `videos/` in the storage root (`backend/data/` or `VOD_STORAGE_ROOT`).
To reclaim the space:
- right after upgrading, before any new VOD is registered: delete that folder (e.g. `rm -rf backend/data/videos`);
- later: keep only the files still referenced by a VOD (`docker compose exec postgres psql -U chzzk -d chzzk -c
  "SELECT storage_key FROM vod"`; each key is a path relative to the storage root) and delete the rest.
방송 have no files to clean up.

## 후원 (Toss Payments test mode)

후원 payments run against Toss Payments in **test mode only** (ADR-0004): approvals are simulated and no money moves,
even if a real card number is entered. The backend confirms every payment with Toss itself; the browser only opens
Toss's 결제창 with the client key the order response hands it.

**Keys.** Real test-mode payments need **your own** Toss test keys. The public 결제창 test keys printed in Toss's docs
and sample repo (the defaults in `application.yml`) are now refused by Toss with `UNAUTHORIZED_KEY`, so with them the
결제창 or the confirm fails. To get working keys:
1. Sign up (free, email only) at the Toss Payments 개발자센터 (https://developers.tosspayments.com).
2. Under API 키, copy the **API 개별 연동** test pair: client key `test_ck_…` and secret key `test_sk_…` (not the
   `test_gck_…`/`test_gsk_…` 결제위젯 pair; the frontend uses the 결제창 SDK, and the server looks orders up by
   `orderId`, which a `gsk` key can't).
3. Put them in the repo-root `.env` (git-ignored; see `.env.example`) and restart `./gradlew bootRun`:
   ```sh
   TOSS_CLIENT_KEY=test_ck_...
   TOSS_SECRET_KEY=test_sk_...
   ```
   The secret key stays on the server; never commit it or put it in the frontend.

**Test mode is enforced.** The backend refuses to start unless both keys start with `test_`. Only to use other keys
on purpose, set `TOSS_ALLOW_NON_TEST_KEYS=true`; this project never handles real money.

None of this is needed for the tests: every backend test replaces Toss with the scriptable fake
`com.chzzk.donation.FakePaymentGateway` (`DonationTestConfig`), and frontend tests mock the API and the payments
module, so no test ever calls Toss.

**Webhooks.** A paid 후원 is confirmed even if the 후원자 never returns to the success page: Toss's
`PAYMENT_STATUS_CHANGED` webhook goes to `POST /internal/payments/webhook`. How it's protected: the event has no
signature, so the backend reads only `paymentKey`/`orderId` from it and **looks the payment up with Toss** (by
`paymentKey`, else by `orderId`); the order settled is the one Toss names, under the same rules as the success page
(`DONE`, our amount, our orderId), and amounts or statuses in the body are ignored. A forged webhook can therefore
only make the backend ask Toss about a payment. Every event is answered `200` (unknown orders and other events are
ignored), except `503` when Toss can't be reached for the lookup, so Toss retries. Repeated webhooks, and a webhook
racing the success page, count once. The endpoint sits under `/internal`, which the Vite dev server doesn't proxy:
Toss must reach the backend itself, over the public internet (it refuses localhost and LAN URLs), and webhooks are
registered per account, so only with your own keys:
1. Expose the backend (port 8080, not Vite's 5173) with a tunnel, e.g. `ngrok http 8080` (or
   `cloudflared tunnel --url http://localhost:8080`), and note the public `https://…` URL.
2. In the 개발자센터, 웹훅 → 웹훅 등록: `https://<tunnel>/internal/payments/webhook`, event `PAYMENT_STATUS_CHANGED`.
3. The tunnel URL changes on each restart (free ngrok), so update the registration when it does. Without a webhook
   everything still works through the success page.

The webhook stays unauthenticated by design (the lookup is the check), so what's left to abuse is cost: anyone can call
it, and each call can make the backend do up to two Toss lookups. It can't change an order, and the values it carries
are logged only at debug level and sanitized. In production, put rate limiting and an allow-list of Toss's webhook IP
addresses in front of `/internal/payments/webhook`; neither is implemented here.

**Expiry.** Orders still PENDING 30 minutes after they were created (Toss's 결제창 is valid that long) become EXPIRED;
a job checks every minute (`donation.expiry.interval`; `donation.expiry.enabled=false` switches it off). EXPIRED orders never count, but if a later confirm or webhook
shows Toss has the payment `DONE` for the order's amount, the order is still confirmed: the money was paid.

### Manual test-payment checklist

With your own test keys in `.env`, the stack running (Run locally), and two 회원 (two browsers, or one private window):
- [ ] 회원 A starts a 방송 (`/broadcasts/new`); 회원 B opens its 시청 page and sees "후원하기" (A, on their own 방송, does not).
- [ ] B picks 5,000원, adds a 메시지 and submits: Toss's test 결제창 opens for 5,000원 and the order name "후원 5,000원".
- [ ] B pays with any card in the test 결제창: it returns to `/donations/success`, which shows "확인 중…" then
      "후원 완료" with 5,000원. In the 개발자센터's 테스트 결제내역 the payment shows as 완료 (no money was taken).
- [ ] Refresh the success page: it shows the same "후원 완료" again, and the 테스트 결제내역 still has exactly one
      payment for that order (confirming again doesn't pay or count twice).
- [ ] B starts another 후원 and closes or cancels the 결제창: it lands on `/donations/fail` ("후원하지 못했습니다") with the reason, and no
      후원 is recorded.
- [ ] A ends the 방송 and tries 삭제 on its 시청 page: it stays, showing "후원 내역이 있는 방송은 삭제할 수 없습니다".

## Tests

```sh
cd backend && ./gradlew test         # HTTP API seam: full Spring context + Testcontainers PostgreSQL
cd frontend && npm test              # Page components: Vitest + Testing Library, network mocked with MSW
cd desktop && npm test               # 데스크톱 앱 renderer: same stack, backend at https://backend.test via MSW
```

Conventions:
- Backend tests act as a visitor or a 회원 only through the real auth API, with `com.chzzk.support.ApiSession` (test
  sources): `ApiSession.visitor(http)` (CSRF token fetched), `ApiSession.member(http)` (a fresh unique 회원, signed up
  and logged in; `.member()` gives its `id`, `nickname`, `email`, `password`), `signUp(...)`, `logIn(...)`. Each
  instance keeps its own `SESSION`/`XSRF-TOKEN` cookies and sends `X-XSRF-TOKEN` on every request, so several 회원 can
  act in one test; use its `get/post/patch/delete` (JSON) or `exchange(path, method, body-or-HttpEntity, type)`.
  `withoutCsrfToken()` and `copy()` cover CSRF rejection and stale-cookie cases (`member.AuthApiTest`).
- Backend tests go through HTTP only (`backend/src/test/.../VodApiTest.java`, `BroadcastApiTest.java`); video storage is rooted in a temp dir. 방송 tests replace the clock (`MutableClock`) and the media server gateway (`FakeMediaServerGateway`) via `BroadcastTestConfig`.
- Frontend tests render routes via `AppRoutes` in a `MemoryRouter` and register MSW handlers per test (`src/test/server.ts`).
  By default everyone is a visitor (`/api/me` → 401) and `GET /api/auth/csrf` answers `CSRF_TOKEN`; call
  `loggedInAs({ id, nickname })` to be a 회원 (it returns `logIn`/`logOut` to switch mid-test). See
  `src/pages/authPages.test.tsx`.
- 회원 auth (ADR-0003): the member module `com.chzzk.member` owns signup, login, the email/password/닉네임 rules
  (`MemberRules`) and BCrypt. Features never touch Spring Security: inject `common.auth.CurrentMember` into the domain
  service and call `require()` (→ `MemberRef(id, nickname)`, or 401 for a visitor) or `find()`. Shared access errors
  live in `common.error`: throw `UnauthenticatedException` (401), `ForbiddenException` (403, not the 소유자/작성자) or
  `ConflictException` (409; `ConflictException.onField(field, msg)` names a form field, `.with(name, value)` adds a
  property such as the existing 방송's id); `ApiProblemHandler` turns them into Problem Details with Korean messages.
  `common.auth.SecurityConfig` holds the URL-level policy (today every URL is reachable and login/ownership are
  enforced in services), the cookie-to-header CSRF (`XSRF-TOKEN` cookie → `X-XSRF-TOKEN` header; MediaMTX's callback
  is exempt) and logout. Sessions live in PostgreSQL (Spring Session JDBC, `SESSION` cookie: HttpOnly, SameSite=Lax,
  7-day idle timeout).
- Frontend 회원 state: `useCurrentMember()` (`src/members/currentMemberContext.ts`) gives `{ member, loading, refresh,
  logOut }`; `CurrentMemberProvider` (rendered by `AppRoutes`) loads `/api/me` once. Guard a screen that needs login
  with `<RequireMember>` (`src/members/RequireMember.tsx`): a visitor goes to `/login?next=<here>`. Link to login with
  `withNext('/login', path)` and only follow `next` through `safeNext` (`src/members/next.ts`, in-app paths only). The
  shared `client.ts` adds the CSRF header to every write by itself; show per-field server errors with
  `apiFieldErrors(error)` and form fields with `forms/Field.tsx`.
- Backend packages: `com.chzzk` is the feature-neutral application root (`BackendApplication`). Each feature is a module beneath it (`com.chzzk.vod` with `domain/`, `storage/`, `web/`; `com.chzzk.broadcast` with `domain/`, `media/`, `web/`). Code shared by features lives in `com.chzzk.common` (e.g. `common.web`, `common.text.Title` for the shared title rule); features don't depend on each other. A feature that needs a 회원 (a VOD's or 방송's 소유자) maps its own read-only view of the `member` table (`vod.domain.VodOwner`, `broadcast.domain.BroadcastOwner`) instead of importing the member module; the API shape of any 회원 is `common.auth.MemberRef` (`{id, nickname}`).
- 방송 status rules, the 송출 키 (`PublishKey`), publish/read authorization and the 60s auto-end (`sweep`) live only in `broadcast.domain.BroadcastLifecycle`; every 방송 중 → 종료 is a conditional update, so 방송 종료 and the auto-end can't both win; `broadcast.PublishingMonitor` feeds it from the media server every 10s (API tests switch its schedule off with `broadcast.publishing-monitor.enabled=false` and call `check()` themselves). MediaMTX is reached only through `broadcast.media.MediaServerGateway` (`MediaMtxGateway`); `MediaPaths` is the one `broadcasts/{id}` mapping.
- Every API error is RFC 7807 Problem Details (Spring `ProblemDetail`: `type, title, status, detail, instance`, plus `errors: [{field, message}]` for field validation, built with `common.web.Problems.fieldError`). `common.web.ApiProblemHandler` covers Spring's own exceptions and the 500 catch-all; each feature maps its domain exceptions in its own `@RestControllerAdvice` ordered `ApiProblemHandler.FEATURE_ORDER` (e.g. `vod.web.VodProblemHandler`). Throw `ResponseStatusException` or a mapped domain exception; don't hand-roll error bodies.
  The one exception is MediaMTX's auth callback (`broadcast.web.MediaServerAuthController`): MediaMTX's contract wants
  an empty 2xx to allow and any other status to deny, so it answers a bare `204`/`401` with no body.
- 댓글 (`com.chzzk.comment`) attach to a `CommentTarget` (`vod(id)` or `broadcast(id)`): `CommentService.list/write/edit/delete`
  checks login and authorship itself; the `comment` table has `vod_id`/`broadcast_id` (exactly one set, both
  `ON DELETE CASCADE`). It reads target existence and the 작성자's 닉네임 straight from the shared tables (native
  exists queries on the `vod` and `broadcast` tables, a read-only view of `member`), so it depends on no other feature
  module; by design that couples it to those table names instead, so renaming them means updating
  `comment.domain.CommentRepository`. Paging is a cursor, newest first by id: `?before=<nextBefore>&size=` for older
  댓글, `?after=<newest id read>&size=` (follow `nextAfter` until `null`) for newer ones when polling. On the
  frontend, `<CommentSection target={{ kind: 'vod' | 'broadcast', id }} />` (`src/comments/`) is the one 댓글 UI, over
  `src/api/comments.ts`.
- Registration form rules live in `frontend/src/vods/validateVodForm.ts` (pure, mirrors the server), separate from how the form submits.
- All frontend data access lives in `frontend/src/api/` (`vods.ts`, `broadcasts.ts`, `auth.ts`, shared `client.ts`); extend it rather than calling the network from pages.
- Only `frontend/src/media/media.ts` touches the camera, `RTCPeerConnection` and WHIP/WHEP; pages call it and page tests `vi.mock` it (`src/pages/broadcastPages.test.tsx`).
