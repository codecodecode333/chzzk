# 05: 후원 있는 방송의 삭제 보호와 README

**What to build:** A 방송 that has received confirmed 후원 can't be deleted, because payment records must not disappear. The 소유자 trying to delete it gets a clear message: "후원 내역이 있는 방송은 삭제할 수 없습니다". A 방송 with only failed or expired orders still deletes normally.

The README explains:
- the 후원 rows in the 공개 범위 table (start = logged-in non-소유자 on a LIVE 방송, confirm/fail = that 후원자, 내역/합계 = public, webhook = provider);
- that payments run in Toss **test mode** with the public docs test keys (no real money), and how to set `TOSS_CLIENT_KEY`/`TOSS_SECRET_KEY`;
- how to receive webhooks with your own Toss developer account and a tunnel;
- a manual checklist for one real test-mode payment and a refresh-doesn't-double check.

**Blocked by:** 01 (후원 주문과 서버 확정)

**Status:** ready-for-agent

- [ ] `DELETE /api/broadcasts/{id}` for a 방송 with any CONFIRMED 후원 → `409` Problem Details with the message above; the broadcast module does this without importing the donations module (a query port or the DB `RESTRICT` constraint mapped to `409`). With only non-confirmed orders, the delete succeeds and those orders are removed or handled consistently with the FK
- [ ] The 시청 screen's 삭제 control shows the `409` message
- [ ] README 공개 범위 table gains the 후원 rows, verified against the code and tests
- [ ] README documents the Toss test-mode keys, env vars, the webhook-with-tunnel setup, and the manual test-payment checklist
- [ ] HTTP API seam tests cover deleting a 방송 with a confirmed 후원 → `409`, and with only FAILED or EXPIRED orders → `204`
- [ ] A frontend seam test covers the `409` message shown on delete
