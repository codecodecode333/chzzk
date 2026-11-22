# 공개 배포는 OCI Always Free VM 한 대에 Compose와 Caddy로 하고, 데스크톱 앱은 서명 없는 Electron 시청 앱으로 GitHub Releases에서 배포한다

공개 서비스는 OCI Always Free VM 한 대(우선 A1.Flex 2 OCPU·12GB, Ubuntu 24.04 aarch64)에서 Docker Compose로 Caddy·백엔드·프론트·PostgreSQL·MediaMTX를 띄운다. 이미지는 VM에서 저장소를 받아 직접 빌드한다. Caddy가 구매한 도메인 `codecodecode333.dev`의 서브도메인 `live.codecodecode333.dev`(루트는 이후 과정 프로젝트들과 서브도메인으로 함께 쓴다; 주소는 환경 값 하나로 바꿀 수 있다)에 Let's Encrypt HTTPS를 자동 발급·갱신하고 경로로 나눈다(`/`, `/api`, `/live`, `/download`, `/healthz`; `/internal`은 닫는다). 비밀 값은 VM의 `.env`에만 두고 운영 설정은 `prod` 프로필로 분리한다. 앞 단계의 로컬 kind/HPA 모드는 그대로 남기되 공개 배포에는 쓰지 않는다.

데스크톱 앱은 Electron으로 만든 로그인 없는 읽기 전용 시청 앱이다(방송 목록과 방송 시청). 화면은 앱 안의 전용 출처에서 띄우고, 백엔드와 MediaMTX는 그 출처에만 공개 조회와 WHEP 재생의 CORS를 연다. GitHub Actions가 Windows x64 설치 파일과 macOS universal `.dmg`를 빌드해 GitHub Releases에 고정 파일명으로 올리고, 다운로드 페이지는 `releases/latest/download/…`로 연결한다. 코드 서명은 하지 않는다.

## Considered Options

- **VM에 k3s/kind로 HPA까지**: VM 한 대에서는 인스턴스를 늘려도 이득이 없고 메모리만 쓴다.
- **E2.1.Micro**: 무료지만 1GB 메모리로는 Spring·PostgreSQL·MediaMTX를 함께 못 돌린다. A1 재고가 끝내 없을 때만의 마지막 수단이다.
- **Tauri**: 결과물이 작지만 macOS WKWebView의 WebRTC 차이와 Rust 빌드 체인이 부담이다. Electron은 두 OS에서 같은 Chromium으로 WHEP·MP4를 재생하고 기존 React/TypeScript 코드를 쓴다.
- **앱이 웹 주소를 그대로 여는 얇은 래퍼**: 만들기는 쉽지만 앱이 백엔드를 직접 조회한다고 보기 어렵다.
- **VM이 설치 파일을 직접 서빙**: 무료 VM의 대역폭을 다운로드가 잡아먹는다.
- **코드 서명/공증**: Apple Developer 연 $99와 Windows 인증서 비용이 든다.

## Consequences

- 설치 때 Windows SmartScreen과 macOS Gatekeeper 경고가 뜬다. 다운로드 페이지와 README에 넘기는 방법을 적는다.
- 자동 업데이트가 없어, 새 버전은 다시 내려받아 설치한다.
- 백엔드는 한 인스턴스이고 VM이 단일 장애점이다. 백업은 이번 범위 밖이다.
- macOS 실행 확인은 실기기가 아니라 GitHub Actions macOS(Apple Silicon) 러너에서 한다. Intel Mac은 빌드에만 포함된다.
