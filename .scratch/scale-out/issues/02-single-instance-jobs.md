# 02: 예약 작업은 매 주기 한 인스턴스만 실행

**What to build:** With several backend instances, 송출 감시 (auto-ending 방송 without media) and 후원 order expiry each run on only one instance per round. Each run first tries a PostgreSQL transaction-scoped advisory lock (one key per job); an instance that doesn't get it skips that round silently. Auto-end after 60s without media and 30-minute expiry keep working. See spec and ADR-0005.

**Blocked by:** None - can start immediately

**Status:** ready-for-agent

- [ ] A small shared helper runs a job body only when its advisory lock is won; no new dependency; the broadcast and donation modules don't import each other
- [ ] 송출 감시 and 후원 만료 both go through it, each with its own key
- [ ] Seam tests on the Testcontainers DB: two concurrent runs of the same job → exactly one runs the body; sequential runs both run; two different jobs don't block each other
- [ ] Existing broadcast auto-end and donation expiry tests still pass
