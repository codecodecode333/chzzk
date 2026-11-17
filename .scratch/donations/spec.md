# 후원: 방송 소유자에게 테스트 결제로 후원하기

Status: ready-for-human

## Problem Statement

치지직 회원은 방송을 보고 댓글은 달 수 있지만, 마음에 드는 방송의 소유자를 금전적으로 응원할 방법이 없다. 방송 소유자도 자기 방송이 얼마나 후원을 받았는지 알 수 없다. 후원은 돈이 걸린 기록이므로 위조되거나 두 번 적립되면 안 되고, "결제 완료" 화면이 떴다는 사실만으로 믿어서도 안 된다.

## Solution

로그인한 회원은 방송 중인 남의 방송 시청 화면에서 "후원하기"를 누른다. 금액(1,000원부터 1,000,000원까지, 1,000원 단위)과 선택 메시지를 고른 뒤, 토스페이먼츠 **테스트 모드** 결제창에서 결제한다. 실제 돈은 움직이지 않는다.

서버는 결제 전에 **후원 주문**을 만들어 금액과 받는 사람(그 방송의 소유자)을 고정한다. 결제가 끝나면 서버가 결제사의 승인 API를 직접 호출해 승인 상태·금액·주문 번호가 일치할 때만 후원을 **확정**한다. 성공 페이지 새로고침이나 웹훅 재시도로 같은 승인 통지가 여러 번 와도 후원은 한 번만 적립된다.

방송 시청 화면은 그 방송의 **후원 합계**와 확정된 후원 내역을 누구에게나 보여 준다. 방송 중에는 이 내용이 자동으로 갱신된다.

## User Stories

### 후원하기
1. As a 회원 watching someone else's live 방송, I want a "후원하기" button, so that I can support its 소유자.
2. As a visitor, I want to see "로그인하고 후원하기" instead, linking to login and back, so that I know how to donate.
3. As a 방송 소유자, I want no 후원 button on my own 방송, and the server to refuse self-후원, so that totals can't be inflated by myself.
4. As a 회원, I want to see no 후원 button on an ended 방송, and the server to refuse starting a 후원 for it, so that I only donate to live 방송.
5. As a 후원자, I want preset amounts (1천·5천·1만·5만원) and a custom amount, so that I can donate quickly or precisely.
6. As a 후원자, I want to be told immediately if my amount is below 1,000원, above 1,000,000원, or not a multiple of 1,000원, so that I can fix it before paying.
7. As a 후원자, I want to add an optional message of up to 100 characters, so that the 소유자 and 시청자 see why I donated.
8. As a 후원자, I want to see the 방송 title and the recipient's 닉네임 before paying, so that I know exactly who I'm supporting.
9. As a 후원자, I want the Toss test payment window to open with the amount I chose, so that I can complete the payment.
10. As a 후원자, I want the app to say clearly that this is test mode and no real money moves, so that I'm not alarmed.

### 확정
11. As a 후원자, I want to see "확인 중…" after paying until the server confirms, so that I don't think it's done prematurely.
12. As a 후원자, I want "후원 완료" only after the server has confirmed the payment with the payment provider, so that a 후원 is never shown as done when it isn't.
13. As a 후원자, I want to be returned to the 방송 I donated to after confirmation, so that I can see my 후원 in its 내역.
14. As a 후원자, I want a clear failure screen with the reason and "다시 시도" when the payment is cancelled, rejected or can't be confirmed, so that I know nothing was recorded.
15. As a 후원자, I want refreshing the success page to never double my 후원, so that I'm not charged or credited twice.
16. As a 후원자, I want my 후원 confirmed even if I close the success page, as long as the payment provider later reports it as paid, so that a paid 후원 isn't lost.
17. As a 방송 소유자, I want the recipient of a 후원 fixed when it was ordered, so that nobody can redirect it to someone else.
18. As a 방송 소유자, I want the amount credited to be exactly the amount the payment provider approved, so that tampered amounts never count.
19. As a 후원자, I want a 후원 whose payment amount doesn't match the order to be rejected and not counted, so that tampering fails safely.
20. As a 후원자, I want a 후원 started for a 방송 that ends while I'm paying to still be confirmed, so that my completed payment counts.

### 내역과 합계
21. As a 시청자, I want to see a 방송's 후원 합계 without logging in, so that I can see how much support it got.
22. As a 시청자, I want to see a 방송's confirmed 후원 내역, newest first, with each 후원자's 닉네임, amount, message and time, so that I can see who supported it.
23. As a 시청자, I want older 후원 to load 20 at a time with "더 보기", so that long histories stay fast.
24. As a 시청자 of a live 방송, I want new 후원 and the 합계 to update on their own every few seconds, so that I can follow along.
25. As a 시청자, I want the 내역 and 합계 to stay visible after the 방송 ends, so that the record remains.
26. As a 시청자, I want only confirmed 후원 to appear and count, so that pending, failed or expired attempts never inflate the 합계.

### 운영과 무결성
27. As a 운영자, I want each 후원 order to be confirmable at most once, even if the same approval arrives via redirect, refresh, webhook and webhook retry, or concurrently, so that no 후원 is credited twice.
28. As a 운영자, I want webhook payloads never trusted as-is but re-checked with the payment provider, so that forged webhooks can't create 후원.
29. As a 운영자, I want pending orders that never complete to expire after 30 minutes, so that stale orders don't linger.
30. As a 운영자, I want an expired order to still be confirmed if the payment provider later shows it as paid, so that real payments are never dropped.
31. As a 방송 소유자, I want deleting a 방송 that has confirmed 후원 refused with a clear message, so that payment records aren't lost.
32. As a 운영자, I want the payment provider's secret key kept only on the server, never sent to the browser, so that it can't be abused.
33. As a 운영자, I want only the 후원자 who created an order to confirm or fail it, so that others can't interfere with someone's payment.
34. As a developer, I want to run the whole flow locally in Toss test mode with my own free Toss developer-account test keys (`test_ck_…`/`test_sk_…`), so that the feature can be demoed without real money. (The public sample keys in Toss's docs no longer work: Toss refuses them with `UNAUTHORIZED_KEY`. Automated tests need no keys at all: they use a fake payment gateway.)
35. As a developer, I want README instructions for receiving webhooks with my own Toss developer account and a tunnel, so that the webhook path can be exercised end to end.

## Implementation Decisions

**Domain vocabulary** (see `GLOSSARY.md`, new 후원 section): **후원**, **후원자**, **후원 금액**, **후원 메시지**, **후원 합계**. The recipient of a 후원 is the 방송's **소유자**. "방송자" from the assignment maps to that 소유자. "도네이션" is UI copy only.

**Architecture** (see ADR-0004): server-created order, then payment, then server-side confirmation through the payment provider. The provider is **Toss Payments, test mode, 결제창 (API 개별 연동)**, using the publicly documented `test_ck_…`/`test_sk_…` keys. These are configurable through env, and the secret key never reaches the browser. The 주문서형/위젯 keys are not used, because orderId lookup doesn't work with them.

**Modules (backend)**
- **Donations feature module** (new, deep):
  - `start(broadcastId, amount, message)` creates a `PENDING` order. It checks login, that the 방송 is LIVE, that it isn't the caller's own 방송, and the amount and message rules.
  - `confirm(paymentKey, orderId, amount)` checks that the caller is the order's 후원자 and that the amount equals the order amount. It then calls the gateway confirm, verifies `status == DONE`, `totalAmount == order.amount` and `orderId` match, and transitions `PENDING|EXPIRED → CONFIRMED`. On any mismatch or rejection it sets `FAILED`.
  - `fail(orderId, code, message)` is called by the 후원자 and moves `PENDING → FAILED`.
  - `reconcile(paymentKey|orderId)` handles webhooks: it looks the payment up through the gateway and applies the same confirm rules, with no caller-identity check.
  - `expire(now)` moves `PENDING` orders older than 30 minutes to `EXPIRED`.
  - `list(broadcastId, cursor)` and `total(broadcastId)` read `CONFIRMED` 후원 only.
- **Payment gateway** (interface + Toss implementation + test fake): `confirm(paymentKey, orderId, amount, idempotencyKey)` and `lookup(paymentKey)` / `lookupByOrderId(orderId)` return a normalized result `{status, totalAmount, orderId, paymentKey, approvedAt, failureCode}`.
  - The Toss implementation uses Basic auth with the secret key and sends `Idempotency-Key = orderId` on confirm.
  - `ALREADY_PROCESSED_PAYMENT` is resolved by looking the payment up.
  - Network errors leave the order `PENDING`, so a later retry or webhook can still confirm it.
- **Scheduled job**: runs the expiry, reusing the existing scheduling style with an injectable clock.
- **Broadcast module**: deleting a 방송 with any `CONFIRMED` 후원 → `409`, and the DB FK backs this up with `RESTRICT`. To keep features independent, the check goes through a tiny query port or the DB constraint, not by importing the donations module.

**Idempotency** (the core requirement)
- Unique constraints on `order_id` and `payment_key`.
- The state transition is a conditional atomic update (`… WHERE status IN ('PENDING','EXPIRED')`). Exactly one caller wins; the others read and return the current state.
- Confirming an already-`CONFIRMED` order returns the existing 후원 (`200`), unchanged.
- `total` is computed only from `CONFIRMED` rows, so repeated notices can never double-count.

**Order state machine**: `PENDING → CONFIRMED | FAILED | EXPIRED`, and `EXPIRED → CONFIRMED` only when the gateway lookup shows DONE. `CONFIRMED` and `FAILED` are terminal.

**API contract** (errors are Problem Details; Korean messages)
- `POST /api/broadcasts/{id}/donations` `{amount, message?}` → `201 {orderId, orderName, amount, clientKey, successUrl, failUrl, recipient:{id,nickname}}`.
  - Not logged in → `401`.
  - Own 방송 → `403`.
  - Unknown 방송 → `404`.
  - Ended 방송 → `409`.
  - Bad amount or message → `400`.
- `POST /api/donations/confirm` `{paymentKey, orderId, amount}` → `200 {donation}`.
  - Not logged in → `401`.
  - Not the order's 후원자 → `403`.
  - Unknown order → `404`.
  - Amount mismatch or provider rejection → `400`, with a provider failure code when present.
  - Gateway unreachable → `503`, and the order stays PENDING.
  - Already confirmed → `200`, with the same 후원.
- `POST /api/donations/{orderId}/fail` `{code, message}` → `204`; 후원자 only.
- `GET /api/broadcasts/{id}/donations?before=&after=&size=` → `{content:[{id, donor:{id,nickname}, amount, message, confirmedAt}], nextBefore, nextAfter, total}`.
  - Public.
  - CONFIRMED only, ordered by confirmation (id) DESC.
  - Cursors work like 댓글.
- `POST /internal/payments/webhook` → always `200` quickly. It extracts paymentKey/orderId from a `PAYMENT_STATUS_CHANGED` payload, reconciles through the gateway, and ignores unknown or irrelevant events. It must not be proxied publicly by the Vite dev server beyond what's needed.

**Schema** (a new Flyway migration): a `donation` table.

| Column | Type |
|---|---|
| `id` | BIGINT identity |
| `order_id` | UUID unique NOT NULL |
| `broadcast_id` | NOT NULL, FK RESTRICT |
| `donor_id` | NOT NULL, FK member |
| `recipient_id` | NOT NULL, FK member |
| `amount` | INT NOT NULL, CHECK range and multiple of 1000 |
| `message` | VARCHAR(100) NULL |
| `status` | NOT NULL, CHECK (`PENDING`/`CONFIRMED`/`FAILED`/`EXPIRED`) |
| `payment_key` | VARCHAR unique NULL |
| `failure_code` | NULL |
| `created_at` | |
| `confirmed_at` | NULL |

- Indexes: `(broadcast_id, status, id DESC)` for listing, and `(status, created_at)` for expiry.
- A CHECK constraint enforces `donor_id <> recipient_id`.

**Frontend**
- **Toss SDK**: the official Toss SDK (v2) is loaded behind a thin payments module, mirroring the media module. It is the only code that touches Toss, which keeps pages testable.
- **Donate entry point**: the 방송 시청 screen shows "후원하기" for logged-in non-소유자 while LIVE, and "로그인하고 후원하기" to visitors.
- **후원 modal**: preset amounts plus a custom input, client-side validation mirroring the server, the optional message, the recipient 닉네임, and a "테스트 결제" notice. On submit it creates the order and then opens the Toss payment window with the server's orderId and amount.
- **`/donations/success`**: reads `paymentKey`, `orderId` and `amount` from the query and calls confirm.
  - While waiting it shows "확인 중…".
  - On `200` it shows "후원 완료" and links back to the 방송.
  - On error it shows the failure.
  - It is safe to refresh, because confirm is idempotent.
- **`/donations/fail`**: reads `code`, `message` and `orderId`, calls the fail endpoint, and shows the reason with "다시 시도".
- **시청 screen**: shows a 후원 panel above the comments with the 후원 합계 and the 내역 (newest first, "더 보기"). While LIVE it polls every 5s using the `after` cursor, which also refreshes the 합계.

**Config/README**
- `TOSS_CLIENT_KEY` and `TOSS_SECRET_KEY` in `.env`, set to the developer's own Toss developer-account test keys (the defaults are the public docs sample keys, which Toss now refuses with `UNAUTHORIZED_KEY`), plus a clear "test mode only" note; the backend refuses to start with keys that aren't `test_` keys unless explicitly overridden.
- Webhook setup with your own developer-center account and a tunnel.
- The 공개 범위 table gains the 후원 rows.

## Testing Decisions

- Good tests exercise external behaviour only. On the backend that means HTTP in and out; on the frontend, rendered UI and interaction. They never assert on internal tables or the gateway's internals. The existing API and page tests are the model.
- **Seam 1: backend HTTP API** (prior art: the existing API tests with Testcontainers, the `ApiSession` login helper, the injectable clock and fake gateways). A **fake `PaymentGateway`** scripts the provider's answers.
  - **Start**: anonymous `401`, own 방송 `403`, ENDED `409`, unknown `404`, amount and message rules `400`, and success returning the recipient.
  - **Confirm**:
    - success, then it appears in the list and the total;
    - amount mismatch, from the client or from the provider's `totalAmount`, gives `400` and FAILED, and nothing is counted;
    - provider rejection;
    - another 회원 confirming gives `403`;
    - gateway unreachable gives `503` and the order stays PENDING, and a later confirm succeeds.
  - **Idempotency**:
    - confirm twice returns the same 후원 and the total is counted once;
    - confirm then webhook, or webhook then confirm, counts once;
    - webhook retried counts once;
    - two concurrent confirms give exactly one CONFIRMED and the total counted once;
    - `ALREADY_PROCESSED_PAYMENT` from the provider is resolved through lookup.
  - **Webhook**: forged payload amounts are ignored in favour of the lookup; an unknown order is ignored with `200`.
  - **Expiry**: PENDING becomes EXPIRED after 30 minutes on the advanced clock; a late webhook or confirm with DONE still confirms it.
  - **List**: public, CONFIRMED only, cursors, total.
  - **Deletion**: deleting a 방송 with confirmed 후원 gives `409`, and so does one with a PENDING or EXPIRED order (its payment may still be approved); one with only failed orders deletes fine.
- **Seam 2: frontend page components** (prior art: the existing page tests with MSW and `loggedInAs`). The **payments module is mocked**.
  - **Button visibility**: as visitor, as 회원 and as 소유자, on LIVE and on ENDED.
  - **Modal**: validation and presets; submit creates the order and calls the payments module with the server's values.
  - **Success page**: shows "확인 중…", then "후원 완료" only after the confirm `200`, and the error state otherwise.
  - **Fail page**: calls the fail endpoint.
  - **Panel**: the 합계 and 내역, "더 보기", and LIVE polling picking up a new 후원 (fake timers).
- **Manual (not automated)**: one real Toss **test-mode** payment through the actual payment window, using the developer's own test keys, on the local HTTPS dev setup. Confirm that the 후원 appears and that refreshing the success page doesn't double it. This is recorded as a README checklist item. No real money moves.

## Out of Scope

- Refunds and cancellations, partial cancellation, settlement or payout to 소유자, and 소유자 earnings dashboards
- 후원 to VODs; 후원 rankings, goals and leaderboards
- On-stream 후원 alerts (TTS, overlays) and real-time push (SSE/WebSocket)
- Payment methods other than what the Toss test 결제창 offers by default; virtual-account async deposits beyond reconciling a DONE status
- Production (live) keys, real money, and webhook signature verification beyond re-lookup
- Rate limiting of 후원 attempts

## Further Notes

- The public Toss sample test keys are dead (`UNAUTHORIZED_KEY`), so a real test-mode payment needs the developer's own free Toss developer account and its `test_ck_`/`test_sk_` keys; automated tests use a fake gateway and never call Toss. Webhooks are registered per account and need a public URL, which is why the redirect-confirm path is primary and the webhook path is verified by API tests plus optional self-setup.
- Toss requires confirmation within 10 minutes of payment authentication. The 30-minute expiry only cleans up abandoned orders; reconciliation by lookup covers late completions.
- Because the recipient is frozen at order time and a 방송's 소유자 never changes, the recipient is always the 방송 소유자, but storing it keeps the record self-contained.
- This feature makes a 방송 with 후원 undeletable. The README access table and 방송 delete UI should mention it.
