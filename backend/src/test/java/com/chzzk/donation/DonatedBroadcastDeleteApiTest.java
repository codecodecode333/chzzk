package com.chzzk.donation;

import static com.chzzk.donation.DonationApiTest.approval;
import static com.chzzk.donation.DonationApiTest.confirm;
import static com.chzzk.donation.DonationApiTest.order;
import static org.assertj.core.api.Assertions.assertThat;

import com.chzzk.broadcast.BroadcastTestConfig;
import com.chzzk.broadcast.MutableClock;
import com.chzzk.common.job.JobRounds;
import com.chzzk.support.ApiSession;
import java.time.Duration;
import java.util.Map;
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
 * HTTP API seam: deleting a 방송 that has 후원 orders. Payment records must not disappear, so a 방송 with any
 * confirmed 후원 can't be deleted, nor one with an order that may still be paid (PENDING or EXPIRED: a late approval
 * still confirms it); only FAILED orders, which never become 후원, are deleted with it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"broadcast.publishing-monitor.enabled=false", "donation.expiry.enabled=false",
                "donation.toss.client-key=test_ck_for_tests"})
@Testcontainers
@Import({BroadcastTestConfig.class, DonationTestConfig.class})
class DonatedBroadcastDeleteApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TestRestTemplate http;

    @Autowired
    FakePaymentGateway payments;

    @Autowired
    MutableClock clock;

    @Autowired
    DonationExpiryJob expiry;

    @Autowired
    JdbcTemplate jdbc;

    ApiSession owner;
    long broadcastId;

    @BeforeEach
    void liveBroadcast() {
        payments.reset();
        owner = ApiSession.member(http);
        ResponseEntity<Map<String, Object>> started = owner.post("/api/broadcasts", Map.of("title", "후원 받을 방송"));
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        broadcastId = ((Number) started.getBody().get("id")).longValue();
    }

    void end() {
        assertThat(owner.post("/api/broadcasts/" + broadcastId + "/end", null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    ResponseEntity<Map<String, Object>> delete() {
        return owner.delete("/api/broadcasts/" + broadcastId);
    }

    HttpStatus statusOfBroadcast() {
        return HttpStatus.valueOf(owner.get("/api/broadcasts/" + broadcastId).getStatusCode().value());
    }

    @Test
    void aBroadcastWithAConfirmedDonationCannotBeDeleted() {
        ApiSession donor = ApiSession.member(http);
        Map<String, Object> paid = order(donor, broadcastId, 5_000);
        assertThat(confirm(donor, approval(paid, "tgen_paid", 5_000)).getStatusCode()).isEqualTo(HttpStatus.OK);
        order(donor, broadcastId, 1_000); // an unpaid order alongside changes nothing
        end();

        ResponseEntity<Map<String, Object>> deleted = delete();

        assertThat(deleted.getStatusCode()).as("%s", deleted.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(deleted.getBody()).containsEntry("detail", "후원 내역이 있는 방송은 삭제할 수 없습니다");
        assertThat(statusOfBroadcast()).isEqualTo(HttpStatus.OK);
        // the confirmed 후원 is still there: confirming it again returns it as-is
        assertThat(confirm(donor, approval(paid, "tgen_paid", 5_000)).getBody()).containsEntry("status", "CONFIRMED");
    }

    static final String PAYMENT_IN_PROGRESS = "결제가 진행 중인 후원이 있어 지금은 삭제할 수 없습니다";

    void failOrder(ApiSession donor, Map<String, Object> order) {
        assertThat(donor.post("/api/donations/" + order.get("orderId") + "/fail",
                Map.of("code", "PAY_PROCESS_ABORTED", "message", "결제가 중단되었습니다.")).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void aBroadcastWithOnlyFailedOrdersIsDeletedWithThem() {
        ApiSession donor = ApiSession.member(http);
        Map<String, Object> failed = order(donor, broadcastId, 1_000);
        failOrder(donor, failed);
        end();

        ResponseEntity<Map<String, Object>> deleted = delete();

        assertThat(deleted.getStatusCode()).as("%s", deleted.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(statusOfBroadcast()).isEqualTo(HttpStatus.NOT_FOUND);
        // the failed order went with it
        assertThat(confirm(donor, approval(failed, "tgen_late", 1_000)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(payments.confirms()).isEmpty();
    }

    @Test
    void aBroadcastWithAPendingOrderCannotBeDeletedUntilThePaymentSettles() {
        ApiSession donor = ApiSession.member(http);
        Map<String, Object> pending = order(donor, broadcastId, 2_000);
        end();

        ResponseEntity<Map<String, Object>> deleted = delete();

        assertThat(deleted.getStatusCode()).as("%s", deleted.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(deleted.getBody()).containsEntry("detail", PAYMENT_IN_PROGRESS);
        assertThat(statusOfBroadcast()).isEqualTo(HttpStatus.OK);
        // the payment arrives late after all: it is a 후원, and the 방송 keeps it
        assertThat(confirm(donor, approval(pending, "tgen_late", 2_000)).getBody()).containsEntry("status", "CONFIRMED");
        assertThat(delete().getBody()).containsEntry("detail", "후원 내역이 있는 방송은 삭제할 수 없습니다");
    }

    @Test
    void aBroadcastWithAnExpiredOrderCannotBeDeleted() {
        ApiSession donor = ApiSession.member(http);
        Map<String, Object> expired = order(donor, broadcastId, 2_000);
        end();
        clock.advance(Duration.ofMinutes(31));
        JobRounds.intervalPasses(jdbc, DonationExpiryJob.JOB);
        expiry.expire();

        ResponseEntity<Map<String, Object>> deleted = delete();

        assertThat(deleted.getStatusCode()).as("%s", deleted.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(deleted.getBody()).containsEntry("detail", PAYMENT_IN_PROGRESS);
        // a late approval still confirms an EXPIRED order: it must still be there
        assertThat(confirm(donor, approval(expired, "tgen_expired_late", 2_000)).getBody())
                .containsEntry("status", "CONFIRMED");
    }

    @Test
    void deletingWhileTossIsApprovingThePaymentLosesNoPaidDonation() throws Exception {
        ApiSession donor = ApiSession.member(http);
        Map<String, Object> order = order(donor, broadcastId, 5_000);
        end();
        FakePaymentGateway.Hold atToss = payments.holdNextConfirm();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ResponseEntity<Map<String, Object>>> confirming =
                    pool.submit(() -> confirm(donor, approval(order, "tgen_mid_delete", 5_000)));
            atToss.awaitArrival(); // Toss has approved: the 후원자 has paid, our answer is on its way

            ResponseEntity<Map<String, Object>> deleted = delete();
            atToss.release();

            assertThat(deleted.getStatusCode()).as("%s", deleted.getBody()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(deleted.getBody()).containsEntry("detail", PAYMENT_IN_PROGRESS);
            ResponseEntity<Map<String, Object>> confirmed = confirming.get();
            assertThat(confirmed.getStatusCode()).as("%s", confirmed.getBody()).isEqualTo(HttpStatus.OK);
            assertThat(confirmed.getBody()).containsEntry("status", "CONFIRMED");
        } finally {
            pool.shutdownNow();
        }
        ResponseEntity<Map<String, Object>> history = owner.get(DonationApiTest.donationsOf(broadcastId));
        assertThat(history.getBody()).containsEntry("total", 5_000);
    }
}
