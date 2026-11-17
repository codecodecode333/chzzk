package com.chzzk.donation;

import static org.assertj.core.api.Assertions.assertThat;

import com.chzzk.broadcast.BroadcastTestConfig;
import com.chzzk.donation.FakePaymentGateway.ConfirmCall;
import com.chzzk.donation.payment.PaymentStatus;
import com.chzzk.support.ApiSession;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * HTTP API seam for 후원 orders and their server-side confirmation (ADR-0004): full Spring context against
 * Testcontainers PostgreSQL, with Toss replaced by the scriptable {@link FakePaymentGateway}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"broadcast.publishing-monitor.enabled=false", "donation.expiry.enabled=false",
                "donation.toss.client-key=test_ck_for_tests"})
@Testcontainers
@Import({BroadcastTestConfig.class, DonationTestConfig.class})
class DonationApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TestRestTemplate http;

    @Autowired
    FakePaymentGateway payments;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void resetPayments() {
        payments.reset();
    }

    /** A 방송 중 방송 started by a fresh 회원 (one 방송 중 방송 per 회원). */
    record Live(ApiSession owner, long id) {}

    Live live() {
        ApiSession owner = ApiSession.member(http);
        ResponseEntity<Map<String, Object>> started = owner.post("/api/broadcasts", Map.of("title", "후원 받을 방송"));
        assertThat(started.getStatusCode()).as("start: %s", started.getBody()).isEqualTo(HttpStatus.CREATED);
        return new Live(owner, ((Number) started.getBody().get("id")).longValue());
    }

    static void end(Live b) {
        assertThat(b.owner().post("/api/broadcasts/" + b.id() + "/end", null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    static String donationsOf(long broadcastId) {
        return "/api/broadcasts/" + broadcastId + "/donations";
    }

    static Map<String, Object> donation(int amount, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("amount", amount);
        body.put("message", message);
        return body;
    }

    /** {@code donor} orders a 후원 to the 방송, asserting {@code 201}; returns the order. */
    static Map<String, Object> order(ApiSession donor, long broadcastId, int amount) {
        ResponseEntity<Map<String, Object>> ordered = donor.post(donationsOf(broadcastId), donation(amount, "응원해요"));
        assertThat(ordered.getStatusCode()).as("order: %s", ordered.getBody()).isEqualTo(HttpStatus.CREATED);
        return ordered.getBody();
    }

    /** What the success page sends: Toss's paymentKey plus the orderId and amount from the successUrl's query. */
    static Map<String, Object> approval(Map<String, Object> order, String paymentKey, Object amount) {
        return Map.of("paymentKey", paymentKey, "orderId", order.get("orderId"), "amount", amount);
    }

    static ResponseEntity<Map<String, Object>> confirm(ApiSession who, Map<String, Object> approval) {
        return who.post("/api/donations/confirm", approval);
    }

    static Map<String, Object> ref(ApiSession session) {
        return Map.of("id", (int) session.member().id(), "nickname", session.member().nickname());
    }

    // ---- start ----

    @Test
    void memberOrdersADonationToSomeoneElsesLiveBroadcast() {
        Live b = live();
        ApiSession donor = ApiSession.member(http);

        ResponseEntity<Map<String, Object>> ordered = donor.post(donationsOf(b.id()), donation(5_000, "  응원해요  "));

        assertThat(ordered.getStatusCode()).as("%s", ordered.getBody()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = ordered.getBody();
        assertThat(body).containsOnlyKeys("orderId", "orderName", "amount", "clientKey", "successUrl", "failUrl",
                "recipient");
        String orderId = (String) body.get("orderId");
        assertThat(UUID.fromString(orderId)).hasToString(orderId);
        assertThat(body)
                .containsEntry("orderName", "후원 5,000원")
                .containsEntry("amount", 5_000)
                .containsEntry("clientKey", "test_ck_for_tests")
                .containsEntry("successUrl", "/donations/success")
                .containsEntry("failUrl", "/donations/fail?orderId=" + orderId + "&broadcastId=" + b.id())
                .containsEntry("recipient", ref(b.owner()));
        assertThat(payments.confirms()).isEmpty();
    }

    @Test
    void eachOrderGetsItsOwnOrderId() {
        Live b = live();
        ApiSession donor = ApiSession.member(http);

        assertThat(order(donor, b.id(), 1_000).get("orderId")).isNotEqualTo(order(donor, b.id(), 1_000).get("orderId"));
    }

    @Test
    void visitorCannotOrder() {
        ResponseEntity<Map<String, Object>> ordered = ApiSession.visitor(http).post(donationsOf(live().id()),
                donation(1_000, null));

        assertThat(ordered.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ordered.getBody()).containsEntry("detail", "로그인이 필요합니다.");
    }

    @Test
    void ownerCannotDonateToTheirOwnBroadcast() {
        Live b = live();

        ResponseEntity<Map<String, Object>> ordered = b.owner().post(donationsOf(b.id()), donation(1_000, null));

        assertThat(ordered.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ordered.getBody()).containsEntry("detail", "내 방송에는 후원할 수 없습니다.");
    }

    @Test
    void unknownBroadcastIs404() {
        assertThat(ApiSession.member(http).post(donationsOf(999_999), donation(1_000, null)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void endedBroadcastIs409() {
        Live b = live();
        end(b);

        ResponseEntity<Map<String, Object>> ordered = ApiSession.member(http).post(donationsOf(b.id()),
                donation(1_000, null));

        assertThat(ordered.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ordered.getBody()).containsEntry("detail", "종료된 방송에는 후원할 수 없습니다.");
    }

    @Test
    void amountMustBe1000To1000000InStepsOf1000() {
        long id = live().id();
        ApiSession donor = ApiSession.member(http);
        String rule = "후원 금액은 1,000원부터 1,000,000원까지 1,000원 단위로 입력해 주세요.";

        for (Integer amount : new Integer[] {999, 1_500, 0, -1_000, 1_001_000}) {
            ResponseEntity<Map<String, Object>> ordered = donor.post(donationsOf(id), donation(amount, null));
            assertThat(ordered.getStatusCode()).as("amount %s", amount).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(ordered.getBody()).containsEntry("errors", List.of(Map.of("field", "amount", "message", rule)));
        }
        Map<String, Object> noAmount = new HashMap<>();
        noAmount.put("message", "금액 없음");
        ResponseEntity<Map<String, Object>> missing = donor.post(donationsOf(id), noAmount);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getBody()).containsEntry("errors",
                List.of(Map.of("field", "amount", "message", "후원 금액을 입력해 주세요.")));

        assertThat(order(donor, id, 1_000)).containsEntry("amount", 1_000);
        assertThat(order(donor, id, 1_000_000)).containsEntry("amount", 1_000_000);
    }

    @Test
    void messageIsOptionalAndAtMost100Characters() {
        long id = live().id();
        ApiSession donor = ApiSession.member(http);

        ResponseEntity<Map<String, Object>> tooLong = donor.post(donationsOf(id), donation(1_000, "가".repeat(101)));
        assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooLong.getBody()).containsEntry("errors",
                List.of(Map.of("field", "message", "message", "후원 메시지는 100자 이하로 입력해 주세요.")));

        assertThat(donor.post(donationsOf(id), donation(1_000, "😀".repeat(100))).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(donor.post(donationsOf(id), donation(1_000, null)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // ---- confirm ----

    @Test
    void confirmingAPaymentTossApprovedConfirmsTheDonation() {
        Live b = live();
        ApiSession donor = ApiSession.member(http);
        Map<String, Object> order = order(donor, b.id(), 10_000);

        ResponseEntity<Map<String, Object>> confirmed = confirm(donor, approval(order, "tgen_paid", 10_000));

        assertThat(confirmed.getStatusCode()).as("%s", confirmed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(confirmed.getBody())
                .containsEntry("orderId", order.get("orderId"))
                .containsEntry("broadcastId", (int) b.id())
                .containsEntry("amount", 10_000)
                .containsEntry("message", "응원해요")
                .containsEntry("status", "CONFIRMED")
                .containsEntry("donor", ref(donor))
                .containsEntry("recipient", ref(b.owner()))
                .containsEntry("confirmedAt", FakePaymentGateway.APPROVED_AT.toString())
                .containsKey("id");
        // the server confirmed with Toss itself, for the order's amount, idempotently per order
        assertThat(payments.confirms()).containsExactly(
                new ConfirmCall("tgen_paid", (String) order.get("orderId"), 10_000, (String) order.get("orderId")));
    }

    @Test
    void aDonationOrderedWhileLiveIsConfirmedEvenIfTheBroadcastEndedDuringPayment() {
        Live b = live();
        ApiSession donor = ApiSession.member(http);
        Map<String, Object> order = order(donor, b.id(), 1_000);
        end(b);

        ResponseEntity<Map<String, Object>> confirmed = confirm(donor, approval(order, "tgen_late", 1_000));

        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirmed.getBody()).containsEntry("status", "CONFIRMED");
    }

    @Test
    void aClientAmountThatDiffersFromTheOrderFailsItWithoutAskingToss() {
        ApiSession donor = ApiSession.member(http);
        Map<String, Object> order = order(donor, live().id(), 10_000);

        ResponseEntity<Map<String, Object>> tampered = confirm(donor, approval(order, "tgen_cheap", 1_000));

        assertThat(tampered.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tampered.getBody())
                .containsEntry("detail", "결제 금액이 후원 금액과 다릅니다.")
                .containsEntry("code", "AMOUNT_MISMATCH");
        assertThat(payments.confirms()).isEmpty();
        assertFailed(donor, order);
    }

    @Test
    void tossReportingADifferentTotalAmountFailsTheOrder() {
        ApiSession donor = ApiSession.member(http);
        Map<String, Object> order = order(donor, live().id(), 10_000);
        payments.nextConfirm(r -> r.withTotalAmount(1_000));

        ResponseEntity<Map<String, Object>> confirmed = confirm(donor, approval(order, "tgen_odd", 10_000));

        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(confirmed.getBody()).containsEntry("code", "AMOUNT_MISMATCH");
        assertFailed(donor, order);
    }

    @Test
    void tossReportingAnotherOrderOrAPaymentNotDoneFailsTheOrder() {
        ApiSession donor = ApiSession.member(http);
        long id = live().id();
        Map<String, Object> otherOrder = order(donor, id, 1_000);
        payments.nextConfirm(r -> r.withOrderId(UUID.randomUUID().toString()));
        assertThat(confirm(donor, approval(otherOrder, "tgen_other", 1_000)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertFailed(donor, otherOrder);

        // definitively not paid
        for (PaymentStatus status : List.of(PaymentStatus.ABORTED, PaymentStatus.EXPIRED, PaymentStatus.CANCELED)) {
            Map<String, Object> notDone = order(donor, id, 1_000);
            payments.nextConfirm(r -> r.withStatus(status));
            ResponseEntity<Map<String, Object>> confirmed = confirm(donor, approval(notDone, "tgen_" + status, 1_000));
            assertThat(confirmed.getStatusCode()).as("%s", status).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(confirmed.getBody()).containsEntry("code", "PAYMENT_" + status);
            assertFailed(donor, notDone);
        }
    }

    @Test
    void tossReportingAPaymentStillInFlightLeavesTheOrderOpenAndAnswers503() {
        ApiSession donor = ApiSession.member(http);
        long id = live().id();
        for (PaymentStatus status : List.of(PaymentStatus.READY, PaymentStatus.IN_PROGRESS,
                PaymentStatus.WAITING_FOR_DEPOSIT, PaymentStatus.UNKNOWN)) {
            Map<String, Object> order = order(donor, id, 1_000);
            payments.nextConfirm(r -> r.withStatus(status));

            ResponseEntity<Map<String, Object>> notYet = confirm(donor, approval(order, "tgen_" + status, 1_000));

            assertThat(notYet.getStatusCode()).as("%s: %s", status, notYet.getBody())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            // the order is still open: once Toss has it DONE, the retry confirms it
            ResponseEntity<Map<String, Object>> retried = confirm(donor, approval(order, "tgen_" + status, 1_000));
            assertThat(retried.getBody()).as("%s", status).containsEntry("status", "CONFIRMED");
        }
    }

    @Test
    void alreadyProcessedPaymentStillInProgressAtTossLeavesTheOrderOpen() {
        Live b = live();
        ApiSession donor = ApiSession.member(http);
        Map<String, Object> order = order(donor, b.id(), 5_000);
        // a concurrent confirm is being approved: Toss shows the payment IN_PROGRESS for a moment
        payments.holds("tgen_wip", (String) order.get("orderId"), 5_000, PaymentStatus.IN_PROGRESS);
        payments.nextConfirm(FakePaymentGateway.rejects("ALREADY_PROCESSED_PAYMENT", "이미 처리된 결제 입니다."));

        ResponseEntity<Map<String, Object>> notYet = confirm(donor, approval(order, "tgen_wip", 5_000));

        assertThat(notYet.getStatusCode()).as("%s", notYet.getBody()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        payments.approved("tgen_wip", (String) order.get("orderId"), 5_000);
        payments.nextConfirm(FakePaymentGateway.rejects("ALREADY_PROCESSED_PAYMENT", "이미 처리된 결제 입니다."));
        assertThat(confirm(donor, approval(order, "tgen_wip", 5_000)).getBody()).containsEntry("status", "CONFIRMED");
        assertThat(total(b.id())).isEqualTo(5_000);
    }

    @Test
    void anOrderThatVanishesWhileTossApprovesItIsAProblemNotA500() throws Exception {
        ApiSession donor = ApiSession.member(http);
        Map<String, Object> order = order(donor, live().id(), 5_000);
        FakePaymentGateway.Hold atToss = payments.holdNextConfirm();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ResponseEntity<Map<String, Object>>> confirming =
                    pool.submit(() -> confirm(donor, approval(order, "tgen_vanished", 5_000)));
            atToss.awaitArrival();
            // nothing in the app removes an open order any more; simulate it going anyway (e.g. by hand)
            jdbc.update("delete from donation where order_id = ?::uuid", order.get("orderId"));
            atToss.release();

            ResponseEntity<Map<String, Object>> confirmed = confirming.get();
            assertThat(confirmed.getStatusCode()).as("%s", confirmed.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(confirmed.getBody()).containsEntry("detail", "후원 주문을 찾을 수 없습니다. 결제가 승인되었다면 문의해 주세요.");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void tossRejectingThePaymentFailsTheOrderWithTossCode() {
        ApiSession donor = ApiSession.member(http);
        Map<String, Object> order = order(donor, live().id(), 50_000);
        payments.nextConfirm(FakePaymentGateway.rejects("REJECT_CARD_PAYMENT", "한도초과 혹은 잔액부족으로 결제에 실패했습니다."));

        ResponseEntity<Map<String, Object>> confirmed = confirm(donor, approval(order, "tgen_rejected", 50_000));

        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(confirmed.getBody())
                .containsEntry("detail", "한도초과 혹은 잔액부족으로 결제에 실패했습니다.")
                .containsEntry("code", "REJECT_CARD_PAYMENT");
        assertFailed(donor, order);
    }

    @Test
    void tossUnreachableLeavesTheOrderPendingSoALaterConfirmSucceeds() {
        ApiSession donor = ApiSession.member(http);
        Map<String, Object> order = order(donor, live().id(), 1_000);
        payments.nextConfirm(FakePaymentGateway.unreachable());

        ResponseEntity<Map<String, Object>> first = confirm(donor, approval(order, "tgen_retry", 1_000));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        ResponseEntity<Map<String, Object>> retried = confirm(donor, approval(order, "tgen_retry", 1_000));
        assertThat(retried.getStatusCode()).as("%s", retried.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(retried.getBody()).containsEntry("status", "CONFIRMED");
    }

    @Test
    void onlyTheDonorConfirms() {
        ApiSession donor = ApiSession.member(http);
        Map<String, Object> order = order(donor, live().id(), 1_000);

        assertThat(confirm(ApiSession.visitor(http), approval(order, "tgen_x", 1_000)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<Map<String, Object>> byOther = confirm(ApiSession.member(http), approval(order, "tgen_x", 1_000));
        assertThat(byOther.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(byOther.getBody()).containsEntry("detail", "내 후원만 확정할 수 있습니다.");
        assertThat(payments.confirms()).isEmpty();

        // the order is untouched by those attempts
        assertThat(confirm(donor, approval(order, "tgen_x", 1_000)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void unknownOrderIs404() {
        ApiSession member = ApiSession.member(http);

        for (String orderId : List.of(UUID.randomUUID().toString(), "not-a-uuid")) {
            ResponseEntity<Map<String, Object>> confirmed = confirm(member,
                    Map.of("paymentKey", "tgen_x", "orderId", orderId, "amount", 1_000));
            assertThat(confirmed.getStatusCode()).as(orderId).isEqualTo(HttpStatus.NOT_FOUND);
        }
        assertThat(payments.confirms()).isEmpty();
    }

    @Test
    void confirmNeedsPaymentKeyOrderIdAndAmount() {
        ApiSession donor = ApiSession.member(http);
        Map<String, Object> order = order(donor, live().id(), 1_000);

        assertThat(confirm(donor, Map.of("orderId", order.get("orderId"), "amount", 1_000)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(confirm(donor, Map.of("paymentKey", "tgen_x", "orderId", order.get("orderId"))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(payments.confirms()).isEmpty();
    }

    // ---- idempotency: one approval credits one 후원 ----

    /** The 방송's 후원 합계 as anyone sees it, from the public 내역/합계 endpoint. */
    long total(long broadcastId) {
        ResponseEntity<Map<String, Object>> page = ApiSession.visitor(http).get(donationsOf(broadcastId));
        assertThat(page.getStatusCode()).as("%s", page.getBody()).isEqualTo(HttpStatus.OK);
        return ((Number) page.getBody().get("total")).longValue();
    }

    @Test
    void confirmingTwiceReturnsTheSameDonationAndCountsItOnce() {
        Live b = live();
        ApiSession donor = ApiSession.member(http);
        Map<String, Object> order = order(donor, b.id(), 3_000);

        ResponseEntity<Map<String, Object>> first = confirm(donor, approval(order, "tgen_twice", 3_000));
        ResponseEntity<Map<String, Object>> again = confirm(donor, approval(order, "tgen_twice", 3_000));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody()).isEqualTo(first.getBody());
        assertThat(payments.confirms()).hasSize(1); // the second confirm never reaches Toss
        assertThat(total(b.id())).isEqualTo(3_000);
    }

    @Test
    void twoConcurrentConfirmsConfirmOnceAndCountOnce() throws Exception {
        Live b = live();
        ApiSession donor = ApiSession.member(http);
        Map<String, Object> order = order(donor, b.id(), 7_000);
        // Both requests reach Toss together. Like Toss, the fake approves one and answers ALREADY_PROCESSED_PAYMENT to
        // the other, and answers that one first: the worst interleaving for our side.
        payments.race(2);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ResponseEntity<Map<String, Object>>>> results = pool.invokeAll(List.of(
                    () -> confirm(donor, approval(order, "tgen_race", 7_000)),
                    () -> confirm(donor, approval(order, "tgen_race", 7_000))));

            for (Future<ResponseEntity<Map<String, Object>>> result : results) {
                ResponseEntity<Map<String, Object>> confirmed = result.get();
                assertThat(confirmed.getStatusCode()).as("%s", confirmed.getBody()).isEqualTo(HttpStatus.OK);
                assertThat(confirmed.getBody())
                        .containsEntry("status", "CONFIRMED")
                        .containsEntry("orderId", order.get("orderId"));
            }
            assertThat(results.get(0).get().getBody().get("id")).isEqualTo(results.get(1).get().getBody().get("id"));
        } finally {
            pool.shutdownNow();
        }
        assertThat(payments.confirms()).hasSize(2); // both really raced to Toss
        assertThat(total(b.id())).isEqualTo(7_000);
    }

    @Test
    void alreadyProcessedPaymentIsResolvedByLookingThePaymentUp() {
        Live b = live();
        ApiSession donor = ApiSession.member(http);
        Map<String, Object> order = order(donor, b.id(), 5_000);
        // Toss already approved this payment (an earlier confirm whose answer never reached us) ...
        payments.approved("tgen_done", (String) order.get("orderId"), 5_000);
        payments.nextConfirm(FakePaymentGateway.rejects("ALREADY_PROCESSED_PAYMENT", "이미 처리된 결제 입니다."));

        ResponseEntity<Map<String, Object>> confirmed = confirm(donor, approval(order, "tgen_done", 5_000));

        assertThat(confirmed.getStatusCode()).as("%s", confirmed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(confirmed.getBody())
                .containsEntry("status", "CONFIRMED")
                .containsEntry("amount", 5_000)
                .containsEntry("confirmedAt", FakePaymentGateway.APPROVED_AT.toString());
        assertThat(total(b.id())).isEqualTo(5_000);
    }

    @Test
    void alreadyProcessedPaymentIsStillVerifiedAgainstTheOrder() {
        Live b = live();
        ApiSession donor = ApiSession.member(http);
        Map<String, Object> order = order(donor, b.id(), 5_000);
        // the payment Toss has under this key was approved for another amount: never counted
        payments.approved("tgen_cheap_done", (String) order.get("orderId"), 1_000);
        payments.nextConfirm(FakePaymentGateway.rejects("ALREADY_PROCESSED_PAYMENT", "이미 처리된 결제 입니다."));

        ResponseEntity<Map<String, Object>> confirmed = confirm(donor, approval(order, "tgen_cheap_done", 5_000));

        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(confirmed.getBody()).containsEntry("code", "AMOUNT_MISMATCH");
        assertFailed(donor, order);
        assertThat(total(b.id())).isZero();
    }

    @Test
    void alreadyProcessedPaymentTossCannotFindLeavesTheOrderPending() {
        ApiSession donor = ApiSession.member(http);
        Map<String, Object> order = order(donor, live().id(), 1_000);
        payments.nextConfirm(FakePaymentGateway.rejects("ALREADY_PROCESSED_PAYMENT", "이미 처리된 결제 입니다."));

        ResponseEntity<Map<String, Object>> confirmed = confirm(donor, approval(order, "tgen_unknown", 1_000));

        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(confirm(donor, approval(order, "tgen_unknown", 1_000)).getBody()).containsEntry("status", "CONFIRMED");
    }

    @Test
    void gatewayDownAnswers503ProblemAndTheRetryCountsOnce() {
        Live b = live();
        ApiSession donor = ApiSession.member(http);
        Map<String, Object> order = order(donor, b.id(), 2_000);
        payments.nextConfirm(FakePaymentGateway.unreachable());

        ResponseEntity<Map<String, Object>> down = confirm(donor, approval(order, "tgen_down", 2_000));

        assertThat(down.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(down.getBody()).containsEntry("status", 503).containsKey("detail");
        assertThat(total(b.id())).isZero();
        assertThat(confirm(donor, approval(order, "tgen_down", 2_000)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirm(donor, approval(order, "tgen_down", 2_000)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(total(b.id())).isEqualTo(2_000);
    }

    @Test
    void aFailedOrderStaysFailed() {
        Live b = live();
        ApiSession donor = ApiSession.member(http);
        Map<String, Object> order = order(donor, b.id(), 1_000);
        payments.nextConfirm(FakePaymentGateway.rejects("REJECT_CARD_PAYMENT", "한도초과"));
        assertThat(confirm(donor, approval(order, "tgen_dead", 1_000)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertFailed(donor, order);
        assertFailed(donor, order);
        assertThat(total(b.id())).isZero();
    }

    // ---- fail ----

    @Test
    void theDonorReportsAFailedPaymentAndTheOrderCanNoLongerBeConfirmed() {
        ApiSession donor = ApiSession.member(http);
        Map<String, Object> order = order(donor, live().id(), 1_000);
        String path = "/api/donations/" + order.get("orderId") + "/fail";
        Map<String, Object> reason = Map.of("code", "PAY_PROCESS_ABORTED", "message", "결제가 중단되었습니다.");

        assertThat(ApiSession.visitor(http).post(path, reason).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ApiSession.member(http).post(path, reason).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(donor.post("/api/donations/" + UUID.randomUUID() + "/fail", reason).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(donor.post(path, reason).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertFailed(donor, order);
    }

    /** The order is FAILED: confirming it again is refused, and Toss is never asked. */
    void assertFailed(ApiSession donor, Map<String, Object> order) {
        int callsBefore = payments.confirms().size();
        ResponseEntity<Map<String, Object>> again = confirm(donor,
                approval(order, "tgen_again_" + UUID.randomUUID(), order.get("amount")));
        assertThat(again.getStatusCode()).as("%s", again.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody()).containsEntry("detail", "실패한 후원입니다. 다시 후원해 주세요.");
        assertThat(payments.confirms()).hasSize(callsBefore);
    }
}
