# 01: 회원가입·로그인·로그아웃 (walking skeleton)

**What to build:** A visitor signs up with an email, a password and a 닉네임 and is logged in immediately. A 회원 logs in, returns to the page they came from (`?next=`, in-app paths only), and logs out. The header shows the 닉네임 and 로그아웃 while logged in, and 로그인 and 회원가입 while logged out. Login survives a reload and a backend restart, with a 7-day idle expiry.

This ticket lays the auth foundation every later ticket builds on:
- Spring Security with server sessions in PostgreSQL (Spring Session JDBC)
- HttpOnly, SameSite=Lax session cookie
- cookie-to-header CSRF
- BCrypt
- the shared 401/403/409 Problem Details
- one small "current 회원" accessor for features
- a backend test helper that signs up and logs in through the real API (cookie and CSRF)
- a frontend current-member state

See the spec `.scratch/auth-and-comments/spec.md`, ADR-0003 and GLOSSARY (회원 section).

**Blocked by:** None (can start immediately).

**Status:** resolved

- [x] Migrations create the `member` table (email unique case-insensitively, `password_hash`, `nickname` unique, `created_at`) and the Spring Session JDBC tables
- [x] `GET /api/auth/csrf` issues the token cookie, and state-changing requests without a valid CSRF token are rejected
- [x] `POST /api/auth/signup` → `201`, already logged in. Validation: email format, password 8–64, 닉네임 2–20 trimmed. Failures → `400` with field errors; a duplicate email or 닉네임 → `409` naming the field
- [x] `POST /api/auth/login` → `204`. Bad credentials → `401` with the single message "이메일 또는 비밀번호가 올바르지 않습니다"
- [x] `POST /api/auth/logout` → `204`, and the session is invalidated server-side
- [x] `GET /api/me` → `{id, nickname}` or `401`
- [x] Passwords are stored only as BCrypt hashes
- [x] The session cookie is HttpOnly and SameSite=Lax with a 7-day idle timeout, and sessions persist across a backend restart
- [x] The access policy keeps every existing public read public, keeps the MediaMTX internal callback working exactly as today, and leaves existing VOD and 방송 behaviour unchanged (ownership comes in later tickets)
- [x] A current-회원 accessor exists for feature services; shared Problem Details cover 401, 403 and 409 with Korean messages
- [x] The frontend loads the current 회원 at start and refreshes it after signup, login and logout. The HTTP client handles CSRF automatically. `/signup` and `/login` pages exist with validation and server errors; `?next=` is honoured for in-app paths only. The header reflects the state, and logout works
- [x] HTTP API seam tests cover: signup (logged in afterwards), validation, duplicate email (case-insensitive) and 닉네임, login success and failure, logout invalidating the session, `/api/me`, and a write without CSRF rejected. A reusable login helper exists for later tickets
- [x] Frontend seam tests cover: the signup and login forms (validation and server errors), the redirect to `next`, the header in both states, and logout
