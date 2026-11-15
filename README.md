# 치지직 (Chzzk)

로그인 없이 VOD(화면 표기 "다시보기")를 등록하고 시청하는 MVP. 용어는 `GLOSSARY.md`, 설계 결정은 `docs/adr/` 참고.

## Layout

| Path | What |
| --- | --- |
| `backend/` | Spring Boot 3 (Java 21, Gradle Kotlin DSL), Flyway, PostgreSQL |
| `frontend/` | React + Vite + TypeScript + Tailwind CSS |
| `compose.yaml` | Local PostgreSQL only (backend and frontend run natively) |

## Prerequisites

- Docker (for PostgreSQL and for the backend tests, which use Testcontainers)
- Any JDK 17+ to launch Gradle; the Gradle toolchain downloads JDK 21 automatically
- Node.js 24+

## Run locally

```sh
docker compose up -d                 # PostgreSQL on localhost:5432 (db/user/password: chzzk)

cd backend && ./gradlew bootRun      # http://localhost:8080
# Uploaded 영상 파일 are stored under backend/data/ (git-ignored).
# Override with VOD_STORAGE_ROOT, VOD_MAX_FILE_SIZE (default 1GB), DB_URL, DB_USERNAME, DB_PASSWORD.

cd frontend && npm install && npm run dev   # http://localhost:5173, proxies /api to :8080
```

영상 파일 size limit: set `VOD_MAX_FILE_SIZE` (backend, e.g. `512MB`; the multipart request limit is derived from it)
and `VITE_VOD_MAX_FILE_SIZE` (frontend, same format, default `1GB`, read at `npm run dev`/`npm run build` time) to the
same value. The form checks the size first; the server stays authoritative and answers `413` with the configured limit.

## Tests

```sh
cd backend && ./gradlew test         # HTTP API seam: full Spring context + Testcontainers PostgreSQL
cd frontend && npm test              # Page components: Vitest + Testing Library, network mocked with MSW
```

Conventions:
- Backend tests go through HTTP only (`backend/src/test/.../VodApiTest.java`); video storage is rooted in a temp dir.
- Frontend tests render routes via `AppRoutes` in a `MemoryRouter` and register MSW handlers per test (`src/test/server.ts`).
- Backend packages: `com.chzzk` is the feature-neutral application root (`BackendApplication`). Each feature is a module beneath it (`com.chzzk.vod` with `domain/`, `storage/`, `web/`; 방송 goes next to it). Code shared by features lives in `com.chzzk.common` (e.g. `common.web`); features don't depend on each other.
- Every API error is RFC 7807 Problem Details (Spring `ProblemDetail`: `type, title, status, detail, instance`, plus `errors: [{field, message}]` for field validation, built with `common.web.Problems.fieldError`). `common.web.ApiProblemHandler` covers Spring's own exceptions and the 500 catch-all; each feature maps its domain exceptions in its own `@RestControllerAdvice` ordered `ApiProblemHandler.FEATURE_ORDER` (e.g. `vod.web.VodProblemHandler`). Throw `ResponseStatusException` or a mapped domain exception; don't hand-roll error bodies.
- Registration form rules live in `frontend/src/vods/validateVodForm.ts` (pure, mirrors the server), separate from how the form submits.
- All frontend data access lives in `frontend/src/api/vods.ts`; extend it rather than calling the network from pages.
