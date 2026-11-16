# 03: 중복 확정 방지와 결제 실패 처리

**What to build:** A 후원 order is confirmed at most once, however many times the same approval arrives. That covers refreshing the success page, a second confirm request, two confirms at the same moment, and later the webhook. The repeated confirmations return the same 후원, and the 후원 합계 counts it once.

Payment-provider edge cases are handled safely:
- Toss answering `ALREADY_PROCESSED_PAYMENT` is resolved by looking the payment up.
- A provider network failure leaves the order PENDING and answers `503`, so a retry can still confirm it.
- No path ever counts an amount different from the one Toss approved.

**Blocked by:** 01 (후원 주문과 서버 확정)

**Status:** ready-for-agent

- [ ] The PENDING/EXPIRED → CONFIRMED (and → FAILED) transition is a conditional atomic update. Exactly one concurrent caller wins; the others read and return the current state. The unique constraints on `order_id` and `payment_key` back this up
- [ ] Confirming an already-CONFIRMED order returns `200` with the existing 후원 and no gateway call, or a lookup only, and never re-credits
- [ ] The Toss confirm call sends `Idempotency-Key` = orderId. `ALREADY_PROCESSED_PAYMENT` → look the payment up and apply the same verification (`DONE`, amount, orderId)
- [ ] Gateway timeout or network error → `503` Problem Details with the order still PENDING; a later confirm succeeds
- [ ] Confirming a FAILED order does not resurrect it (stays FAILED, clear error)
- [ ] HTTP API seam tests (fake gateway):
  - confirm twice → same 후원, total counted once
  - two concurrent confirms → exactly one CONFIRMED, total once
  - `ALREADY_PROCESSED_PAYMENT` resolved through lookup
  - gateway down → `503` then retry success
  - FAILED stays FAILED
- [ ] The frontend success page tolerates a refresh: a second confirm returning the same 후원 still shows "후원 완료"
