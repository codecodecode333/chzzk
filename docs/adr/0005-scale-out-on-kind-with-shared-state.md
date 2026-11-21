# 로컬 kind 클러스터에서 CPU 기반 HPA로 백엔드를 늘리고, 상태는 모두 인스턴스 밖에 둔다

백엔드를 여러 인스턴스로 띄워 부하에 따라 자동으로 늘리고 줄이기 위해, 단일 노드 kind 클러스터에서 Kubernetes HPA(metrics-server의 CPU 사용률, 목표 50%, 최소 2·최대 5, 축소 안정화 60초)를 쓴다. nginx가 하나의 HTTPS 진입점으로 정적 파일을 내주고 `/api`는 백엔드 Service, `/live`는 MediaMTX로 넘기며, Service가 백엔드 파드들에 요청을 나눈다. 인스턴스가 바뀌어도 아무것도 잃지 않도록 상태는 인스턴스 밖에 둔다: 로그인 세션·VOD·방송·댓글·후원은 PostgreSQL(클러스터 안 1개), 영상 파일은 모든 백엔드 파드가 함께 마운트하는 공유 볼륨이다. 기존 개발 방식(compose + bootRun + Vite)은 그대로 두고 클러스터 모드는 `deploy/`에 따로 둔다.

## Considered Options

- **Docker Desktop Kubernetes**: 추가 설치는 없지만 각자의 Docker Desktop 설정에 달려 재현이 어렵다. kind는 클러스터 설정을 저장소 파일로 고정한다.
- **Docker Compose + 직접 만든 스케일러**: 표준 HPA 대신 지표 수집·판단·조정을 모두 만들고 검증해야 한다.
- **요청 수(RPS) 기준 스케일**: Prometheus와 지표 어댑터가 더 필요하다. CPU는 metrics-server만으로 된다.
- **영상 파일을 MinIO(오브젝트 스토리지)로**: 노드가 여럿이어도 되지만 새 저장소 구현과 진행형 MP4 재생의 Range 처리를 다시 만들어야 한다. 이번 범위에서는 단일 노드 공유 볼륨으로 충분하다.

## Consequences

- 공유 볼륨은 단일 노드에서만 모든 파드가 함께 쓸 수 있다. 노드를 늘리려면 영상 파일을 오브젝트 스토리지로 옮겨야 한다.
- MediaMTX는 1개로 고정한다. 방송 상태는 DB에 있지만 미디어 중계는 연결을 쥔 단일 중계기라 늘리지 않는다.
- 송출 감시와 후원 만료 같은 예약 작업은 PostgreSQL advisory lock으로 매 주기 한 인스턴스만 실행한다. 송출 감시가 기억하는 직전 수신량은 인스턴스 메모리에 있어, 실행 인스턴스가 바뀌면 자동 종료가 한 주기쯤 늦어질 수 있다.
- 축소 때는 graceful shutdown과 readiness 프로브로 진행 중 요청을 끝내고 내려간다.
