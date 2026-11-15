# 라이브 송출·시청은 MediaMTX를 거친 WebRTC(WHIP 송출, WHEP 시청)로 한다

휴대폰 브라우저가 앱 설치 없이 카메라·마이크로 송출해야 하므로 브라우저 표준인 WebRTC를 쓰고, 신호 교환은 표준 WHIP(송출)·WHEP(시청)으로 한다. 중계는 단일 바이너리 오픈소스 미디어 서버 MediaMTX가 맡고, 우리 백엔드는 방송 상태·송출 키만 관리한다. 시청도 WebRTC로 받아 트랜스코딩 없이(H.264/VP8 + Opus 그대로) 1초 미만 지연을 얻는다.

## Considered Options

- **WHIP 송출 + HLS 시청**: 확장성과 CDN 친화성은 좋지만 지연 2~6초에 iOS의 HLS Opus 지원이 불안정해 오디오 트랜스코딩(ffmpeg)이 필요하다.
- **LiveKit(SFU)**: 확장성·SDK가 좋지만 룸/토큰 모델을 가진 무거운 플랫폼이라 이번 범위에 과하다.
- **MediaRecorder → WebSocket → ffmpeg → HLS 자체 구현**: 구현·운영 부담이 크고 지연이 크다.

## Consequences

- 시청자마다 MediaMTX가 WebRTC 연결을 하나씩 중계하므로 동시 시청자 수에 한계가 있다. 시청자가 늘면 MediaMTX의 HLS/LL-HLS 출력(또는 SFU)으로 시청 경로를 바꾸는 것이 다음 수순이다.
- 휴대폰의 카메라 접근에는 보안 컨텍스트가 필요하므로 개발 환경도 HTTPS(LAN + 자체 서명 인증서)로 띄운다.
- 녹화하지 않으므로 종료된 방송은 다시 볼 수 없다. 방송→VOD 전환은 별도 결정이다.
