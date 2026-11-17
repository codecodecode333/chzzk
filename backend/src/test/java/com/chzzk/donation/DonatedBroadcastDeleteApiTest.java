package com.chzzk.donation;

import static com.chzzk.donation.DonationApiTest.approval;
import static com.chzzk.donation.DonationApiTest.confirm;
import static com.chzzk.donation.DonationApiTest.order;
import static org.assertj.core.api.Assertions.assertThat;

import com.chzzk.broadcast.BroadcastTestConfig;
import com.chzzk.support.ApiSession;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * HTTP API seam: deleting a 방송 that has 후원 orders. Payment records must not disappear, so a 방송 with any
 * confirmed 후원 can't be deleted; orders that never became 후원 don't stand in the way.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"broadcast.publishing-monitor.enabled=false", "donation.toss.client-key=test_ck_for_tests"})
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

    @Test
    void aBroadcastWithOnlyFailedOrUnpaidOrdersIsDeletedWithThem() {
        ApiSession donor = ApiSession.member(http);
        Map<String, Object> failed = order(donor, broadcastId, 1_000);
        assertThat(donor.post("/api/donations/" + failed.get("orderId") + "/fail",
                Map.of("code", "PAY_PROCESS_ABORTED", "message", "결제가 중단되었습니다.")).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        Map<String, Object> pending = order(donor, broadcastId, 2_000);
        end();

        ResponseEntity<Map<String, Object>> deleted = delete();

        assertThat(deleted.getStatusCode()).as("%s", deleted.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(statusOfBroadcast()).isEqualTo(HttpStatus.NOT_FOUND);
        // the orders went with it: a late approval of the unpaid one finds no order, and Toss is never asked
        assertThat(confirm(donor, approval(pending, "tgen_late", 2_000)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(payments.confirms()).isEmpty();
    }
}
