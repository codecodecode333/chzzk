# 01: 후원 주문과 서버 확정 (walking skeleton)

**What to build:** A logged-in 회원 watching someone else's live 방송 presses "후원하기", picks an amount (presets or custom), optionally writes a 후원 메시지, and pays in the Toss Payments **test-mode** 결제창. After the redirect back, the success page shows "확인 중…" while the server confirms the payment with Toss (secret key, server-side). It shows "후원 완료" only when the server says the 후원 is confirmed. The fail page shows the reason and "다시 시도".

The server creates the 후원 order before payment, which fixes the amount and the recipient (the 방송's 소유자). It confirms only when Toss reports `DONE` with a matching `totalAmount` and `orderId`.

This ticket lays the foundation:
- the donations feature module
- the `donation` table
- the `PaymentGateway` interface with its Toss implementation and a test fake
- the frontend payments module, the only code touching the Toss SDK
- the success and fail pages

See `.scratch/donations/spec.md`, ADR-0004 and GLOSSARY (후원 section). Toss facts (test keys, SDK, confirm API, error codes) are in the research notes the orchestrator provides.

**Blocked by:** None (can start immediately).

**Status:** ready-for-human

- [x] A Flyway migration creates `donation` as specified: `order_id` UUID unique, `payment_key` unique nullable, `broadcast_id` FK RESTRICT, `donor_id`/`recipient_id` FKs, amount CHECK (1,000–1,000,000, multiple of 1,000), message ≤100, the status CHECK, `donor_id <> recipient_id`, and the indexes
- [x] `POST /api/broadcasts/{id}/donations` `{amount, message?}` → `201 {orderId, orderName, amount, clientKey, successUrl, failUrl, recipient}` and a PENDING order with the recipient fixed. Anonymous → `401`, own 방송 → `403`, unknown → `404`, ENDED → `409`, bad amount or message → `400`
- [x] `POST /api/donations/confirm` `{paymentKey, orderId, amount}`: 후원자 only (`401`/`403`), unknown order → `404`. A client amount that doesn't match the order → `400` and FAILED, with no gateway call. Otherwise it calls the gateway confirm; `DONE` with matching `totalAmount` and `orderId` → CONFIRMED `200` with the 후원; a mismatch or rejection → `400` and FAILED
- [x] `POST /api/donations/{orderId}/fail` `{code, message}` → `204`, PENDING → FAILED; 후원자 only
- [x] `PaymentGateway` interface (confirm and lookup) with a Toss implementation: Basic auth with the secret key from env, defaulting to the public docs **결제창** test keys `test_ck_…`/`test_sk_…`. The secret key never reaches the browser. There is a fake implementation for tests
- [x] Frontend payments module wrapping the Toss SDK (v2); pages depend only on it
- [x] 시청 screen: "후원하기" for logged-in non-소유자 while LIVE, "로그인하고 후원하기" for visitors, nothing for the 소유자 or on ENDED
- [x] 후원 modal: presets 1천·5천·1만·5만, a custom amount and the message, validated client-side to match the server, plus the recipient 닉네임 and a "테스트 결제 — 실제 돈은 나가지 않습니다" notice
- [x] `/donations/success` and `/donations/fail` pages as described; the success page is safe to refresh
- [x] HTTP API seam tests (fake gateway): the start rules matrix, confirm success, client amount mismatch, provider `totalAmount` mismatch, provider rejection, another 회원 confirming → `403`, and fail
- [x] Frontend seam tests (payments module mocked): button visibility per role and state, modal validation and presets, submit calling the payments module with the server's values, success shown only after confirm `200`, and the fail page calling the fail endpoint
- [ ] Manually verified once on the local HTTPS dev setup: a real Toss **test-mode** payment through the 결제창 with the public test keys ends CONFIRMED (no real money moves)
