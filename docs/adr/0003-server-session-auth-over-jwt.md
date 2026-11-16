# 인증은 JWT가 아닌 서버 세션 + HttpOnly 쿠키로 한다

프론트엔드와 API가 같은 출처(개발 시 Vite 프록시)에서 동작하는 SPA이므로, Spring Security의 서버 세션을 HttpOnly·SameSite=Lax 쿠키로 유지하고 세션은 PostgreSQL(Spring Session JDBC)에 저장해 재시작에도 로그인이 유지되게 한다. 쿠키 기반이므로 상태 변경 요청에는 CSRF 토큰을 요구한다.

## Considered Options

- **JWT(액세스 + 리프레시 토큰)**: 다른 출처·모바일 앱·서비스 간 호출이 없어 무상태의 이점이 작다. 반면 토큰 보관 위치(localStorage는 XSS에 노출), 로그아웃 시 즉시 무효화(블랙리스트), 리프레시 회전을 직접 구현해야 한다.

## Consequences

- 로그아웃·강제 만료는 세션 삭제로 즉시 반영된다.
- MediaMTX 인증 콜백은 브라우저 쿠키를 받지 못하므로, 미디어 연결(WHIP)은 계속 송출 키로 인증한다. 송출 키는 로그인한 소유자에게만 발급된다.
- 다른 출처의 클라이언트(네이티브 앱 등)가 생기면 토큰 방식 도입을 다시 검토한다.
