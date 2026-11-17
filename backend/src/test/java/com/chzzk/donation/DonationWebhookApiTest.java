package com.chzzk.donation;

import static com.chzzk.donation.DonationApiTest.approval;
import static com.chzzk.donation.DonationApiTest.confirm;
import static org.assertj.core.api.Assertions.assertThat;

import com.chzzk.broadcast.BroadcastTestConfig;
import com.chzzk.broadcast.MutableClock;
import com.chzzk.donation.payment.PaymentStatus;
import com.chzzk.support.ApiSession;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
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
 * HTTP API seam for Toss's payment-status webhook ({@code POST /internal/payments/webhook}): the webhook only tells the
 * server which payment to look up; whatever the lookup shows goes through the same confirmation rules as the success
 * redirect. Toss is the scriptable {@link FakePaymentGateway}; the webhook is posted like Toss does, with no session.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"broadcast.publishing-monitor.enabled=false", "donation.toss.client-key=test_ck_for_tests"})
@Testcontainers
@Import({BroadcastTestConfig.class, DonationTestConfig.class})
class DonationWebhookApiTest {

    static final String WEBHOOK = "/internal/payments/webhook";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TestRestTemplate http;

    @Autowired
    FakePaymentGateway payments;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    DonationExpiryJob expiry;

    @BeforeEach
    void resetPayments() {
        payments.reset();
    }

    /** A 후원 order by a fresh 회원 to a fresh 방송 중 방송. */
    record Order(ApiSession donor, long broadcastId, String orderId, int amount, Map<String, Object> body) {}

    Order order(int amount) {
        ApiSession owner = ApiSession.member(http);
        ResponseEntity<Map<String, Object>> started = owner.post("/api/broadcasts", Map.of("title", "후원 받을 방송"));
        long broadcastId = ((Number) started.getBody().get("id")).longValue();
        ApiSession donor = ApiSession.member(http);
        Map<String, Object> body = DonationApiTest.order(donor, broadcastId, amount);
        return new Order(donor, broadcastId, (String) body.get("orderId"), amount, body);
    }

    /** A Toss {@code PAYMENT_STATUS_CHANGED} event whose {@code data} claims this payment. */
    static Map<String, Object> event(String paymentKey, String orderId, String status, long totalAmount) {
        Map<String, Object> data = new HashMap<>();
        data.put("paymentKey", paymentKey);
        data.put("orderId", orderId);
        data.put("status", status);
        data.put("totalAmount", totalAmount);
        return Map.of("eventType", "PAYMENT_STATUS_CHANGED", "createdAt", "2026-11-15T10:10:00.000000", "data", data);
    }

    /** Posts like Toss: no session cookie, no CSRF token. */
    ResponseEntity<String> webhook(Object payload) {
        return http.postForEntity(WEBHOOK, payload, String.class);
    }

    /** The 방송's 합계 as the public 후원 내역 API reports it. */
    long total(long broadcastId) {
        ResponseEntity<Map<String, Object>> page = ApiSession.visitor(http)
                .get(DonationApiTest.donationsOf(broadcastId));
        assertThat(page.getStatusCode()).as("%s", page.getBody()).isEqualTo(HttpStatus.OK);
        return ((Number) page.getBody().get("total")).longValue();
    }

    String status(String orderId) {
        return jdbc.queryForObject("select status from donation where order_id = ?::uuid", String.class, orderId);
    }

    @Test
    void aWebhookAloneConfirmsADonationWhoseDonorNeverCameBack() {
        Order o = order(5_000);
        payments.approved("tgen_webhook", o.orderId(), 5_000);

        ResponseEntity<String> answered = webhook(event("tgen_webhook", o.orderId(), "DONE", 5_000));

        assertThat(answered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status(o.orderId())).isEqualTo("CONFIRMED");
        assertThat(total(o.broadcastId())).isEqualTo(5_000);
        assertThat(payments.confirms()).isEmpty(); // looked up, never re-approved
    }

    @Test
    @SuppressWarnings("unchecked")
    void aWebhookFindingTheOrderByOrderIdAloneConfirmsIt() {
        Order o = order(2_000);
        payments.approved("tgen_by_order", o.orderId(), 2_000);
        Map<String, Object> event = event("ignored", o.orderId(), "DONE", 2_000);
        ((Map<String, Object>) event.get("data")).remove("paymentKey");

        assertThat(webhook(event).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status(o.orderId())).isEqualTo("CONFIRMED");
    }

    @Test
    void webhookThenConfirmCountsOnce() {
        Order o = order(3_000);
        payments.approved("tgen_w_c", o.orderId(), 3_000);

        assertThat(webhook(event("tgen_w_c", o.orderId(), "DONE", 3_000)).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map<String, Object>> confirmed = confirm(o.donor(), approval(o.body(), "tgen_w_c", 3_000));

        assertThat(confirmed.getStatusCode()).as("%s", confirmed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(confirmed.getBody()).containsEntry("status", "CONFIRMED").containsEntry("amount", 3_000);
        assertThat(payments.confirms()).isEmpty(); // already a 후원: Toss isn't asked again
        assertThat(total(o.broadcastId())).isEqualTo(3_000);
    }

    @Test
    void confirmThenWebhookCountsOnce() {
        Order o = order(4_000);
        ResponseEntity<Map<String, Object>> confirmed = confirm(o.donor(), approval(o.body(), "tgen_c_w", 4_000));
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(webhook(event("tgen_c_w", o.orderId(), "DONE", 4_000)).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(total(o.broadcastId())).isEqualTo(4_000);
        assertThat(confirm(o.donor(), approval(o.body(), "tgen_c_w", 4_000)).getBody()).isEqualTo(confirmed.getBody());
    }

    @Test
    void aRetriedWebhookCountsOnce() {
        Order o = order(6_000);
        payments.approved("tgen_retry", o.orderId(), 6_000);

        for (int i = 0; i < 3; i++) {
            assertThat(webhook(event("tgen_retry", o.orderId(), "DONE", 6_000)).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        assertThat(total(o.broadcastId())).isEqualTo(6_000);
        assertThat(jdbc.queryForObject("select count(*) from donation where broadcast_id = ?", Long.class,
                o.broadcastId())).isOne();
    }

    @Test
    void aForgedWebhookClaimingAnotherAmountIsIgnoredInFavourOfTheLookup() {
        Order o = order(5_000);
        payments.approved("tgen_real", o.orderId(), 5_000);

        assertThat(webhook(event("tgen_real", o.orderId(), "DONE", 1_000)).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(total(o.broadcastId())).isEqualTo(5_000); // the order's amount, as Toss has it
    }

    @Test
    void aForgedWebhookForAPaymentTossDoesNotHaveChangesNothing() {
        Order o = order(5_000);

        assertThat(webhook(event("tgen_forged", o.orderId(), "DONE", 5_000)).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(status(o.orderId())).isEqualTo("PENDING");
        // the 후원자 can still pay and confirm it normally
        assertThat(confirm(o.donor(), approval(o.body(), "tgen_later", 5_000)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void aForgedWebhookPairingSomeoneElsesOrderWithAnotherPaymentCannotFailIt() {
        Order victim = order(5_000);
        Order other = order(1_000);
        payments.approved("tgen_other", other.orderId(), 1_000);

        webhook(event("tgen_other", victim.orderId(), "DONE", 5_000));

        assertThat(status(victim.orderId())).isEqualTo("PENDING"); // the lookup's order, not the body's, is settled
        assertThat(status(other.orderId())).isEqualTo("CONFIRMED");
    }

    @Test
    void aPaymentTossHasButNotDoneChangesNothing() {
        Order o = order(1_000);
        payments.holds("tgen_in_progress", o.orderId(), 1_000, PaymentStatus.IN_PROGRESS);

        assertThat(webhook(event("tgen_in_progress", o.orderId(), "DONE", 1_000)).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(status(o.orderId())).isEqualTo("PENDING"); // the 후원자's own confirm decides
        assertThat(confirm(o.donor(), approval(o.body(), "tgen_in_progress", 1_000)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void unknownOrdersAndOtherEventsAreIgnoredWith200() {
        payments.approved("tgen_not_ours", "not-our-order", 5_000);

        assertThat(webhook(event("tgen_not_ours", "not-our-order", "DONE", 5_000)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(webhook(event("tgen_nobody", "00000000-0000-0000-0000-000000000000", "DONE", 5_000))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(webhook(Map.of("eventType", "DEPOSIT_CALLBACK", "data", Map.of())).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(webhook(Map.of("hello", "world")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void tossUnreachableForTheLookupAnswers503SoTossRetries() {
        Order o = order(1_000);
        payments.lookupsUnreachable(true);

        assertThat(webhook(event("tgen_down", o.orderId(), "DONE", 1_000)).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(status(o.orderId())).isEqualTo("PENDING");
    }

    // ---- expiry ----

    @Test
    void aPendingOrderExpiresAfterMoreThan30Minutes() {
        Order o = order(1_000);

        clock.advance(Duration.ofMinutes(30));
        expiry.expire();
        assertThat(status(o.orderId())).isEqualTo("PENDING");

        clock.advance(Duration.ofSeconds(1));
        expiry.expire();
        assertThat(status(o.orderId())).isEqualTo("EXPIRED");
        assertThat(total(o.broadcastId())).isZero();
    }

    @Test
    void confirmedAndFailedOrdersNeverExpire() {
        Order confirmed = order(1_000);
        Order failed = order(1_000);
        confirm(confirmed.donor(), approval(confirmed.body(), "tgen_kept", 1_000));
        failed.donor().post("/api/donations/" + failed.orderId() + "/fail", Map.of("code", "PAY_PROCESS_CANCELED"));

        clock.advance(Duration.ofHours(1));
        expiry.expire();

        assertThat(status(confirmed.orderId())).isEqualTo("CONFIRMED");
        assertThat(status(failed.orderId())).isEqualTo("FAILED");
    }

    @Test
    void aLateWebhookShowingThePaymentDoneConfirmsAnExpiredOrder() {
        Order o = order(8_000);
        clock.advance(Duration.ofMinutes(31));
        expiry.expire();
        assertThat(status(o.orderId())).isEqualTo("EXPIRED");
        payments.approved("tgen_late", o.orderId(), 8_000);

        assertThat(webhook(event("tgen_late", o.orderId(), "DONE", 8_000)).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(status(o.orderId())).isEqualTo("CONFIRMED");
        assertThat(total(o.broadcastId())).isEqualTo(8_000);
    }

    @Test
    void aLateConfirmShowingThePaymentDoneConfirmsAnExpiredOrder() {
        Order o = order(9_000);
        clock.advance(Duration.ofMinutes(31));
        expiry.expire();
        assertThat(status(o.orderId())).isEqualTo("EXPIRED");

        ResponseEntity<Map<String, Object>> confirmed = confirm(o.donor(), approval(o.body(), "tgen_late_c", 9_000));

        assertThat(confirmed.getStatusCode()).as("%s", confirmed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(confirmed.getBody()).containsEntry("status", "CONFIRMED");
        assertThat(total(o.broadcastId())).isEqualTo(9_000);
    }

    @Test
    void anExpiredOrderWhosePaymentIsNotDoneStaysExpired() {
        Order o = order(1_000);
        clock.advance(Duration.ofMinutes(31));
        expiry.expire();
        payments.holds("tgen_stale", o.orderId(), 1_000, PaymentStatus.EXPIRED);

        webhook(event("tgen_stale", o.orderId(), "DONE", 1_000));

        assertThat(status(o.orderId())).isEqualTo("EXPIRED");
        assertThat(total(o.broadcastId())).isZero();
    }
}
