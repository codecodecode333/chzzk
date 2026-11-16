# 04: 웹훅 재조회와 대기 주문 만료

**What to build:** A paid 후원 is confirmed even if the 후원자 never comes back to the success page. Toss's payment-status webhook triggers the server to look the payment up and apply exactly the same confirmation rules. The webhook body is never trusted, so a forged webhook can't create or alter a 후원.

Repeated or retried webhooks, and a webhook racing the redirect confirm, still count once. Orders that stay PENDING for 30 minutes become EXPIRED. If a later webhook or confirm shows Toss has the payment as `DONE`, the expired order is still confirmed, because the money was paid.

**Blocked by:** 03 (중복 확정 방지와 결제 실패 처리)

**Status:** ready-for-agent

- [ ] `POST /internal/payments/webhook` accepts Toss `PAYMENT_STATUS_CHANGED` payloads:
  - it extracts `paymentKey`/`orderId`, reconciles through the gateway lookup (by paymentKey, or by orderId), and answers `200` quickly;
  - unknown orders and irrelevant events are ignored with `200`;
  - amounts and statuses in the payload are ignored in favour of the lookup
- [ ] The webhook endpoint is reachable by Toss when the backend is exposed, but not routed through the public `/api` dev proxy unnecessarily; README notes how it's protected (re-lookup) and how to expose it with a tunnel
- [ ] A scheduled job (injectable clock) moves PENDING orders older than 30 minutes to EXPIRED. EXPIRED never counts, but → CONFIRMED is allowed when a lookup shows `DONE` with matching amount and orderId
- [ ] HTTP API seam tests (fake gateway and clock):
  - a webhook alone confirms
  - webhook then confirm, confirm then webhook, and a webhook retried each count once
  - a forged webhook with a wrong amount is ignored in favour of the lookup
  - an unknown order is ignored
  - expiry after more than 30 minutes, and a late webhook or confirm with DONE confirms an EXPIRED order
