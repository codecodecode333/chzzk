package com.chzzk.donation;

import static com.chzzk.donation.DonationApiTest.approval;
import static com.chzzk.donation.DonationApiTest.confirm;
import static com.chzzk.donation.DonationApiTest.donationsOf;
import static com.chzzk.donation.DonationApiTest.order;
import static com.chzzk.donation.DonationApiTest.ref;
import static org.assertj.core.api.Assertions.assertThat;

import com.chzzk.broadcast.BroadcastTestConfig;
import com.chzzk.donation.payment.PaymentRejectedException;
import com.chzzk.support.ApiSession;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * HTTP API seam for a 방송's 후원 내역 and 합계: public, CONFIRMED 후원 only, newest first, paged like 댓글. 후원 are
 * seeded through the real order and confirm flow, with Toss replaced by the {@link FakePaymentGateway}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"broadcast.publishing-monitor.enabled=false", "donation.toss.client-key=test_ck_for_tests"})
@Testcontainers
@Import({BroadcastTestConfig.class, DonationTestConfig.class})
class DonationListApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TestRestTemplate http;

    @Autowired
    FakePaymentGateway payments;

    @BeforeEach
    void resetPayments() {
        payments.reset();
    }

    long liveBroadcast() {
        ResponseEntity<Map<String, Object>> started = ApiSession.member(http)
                .post("/api/broadcasts", Map.of("title", "후원 받을 방송"));
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) started.getBody().get("id")).longValue();
    }

    /** {@code donor} orders and pays a 후원; returns its id. */
    long donate(ApiSession donor, long broadcastId, int amount) {
        ResponseEntity<Map<String, Object>> confirmed = confirm(donor,
                approval(order(donor, broadcastId, amount), "tgen_" + UUID.randomUUID(), amount));
        assertThat(confirmed.getStatusCode()).as("%s", confirmed.getBody()).isEqualTo(HttpStatus.OK);
        return ((Number) confirmed.getBody().get("id")).longValue();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> content(ResponseEntity<Map<String, Object>> page) {
        assertThat(page.getStatusCode()).as("%s", page.getBody()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) page.getBody().get("content");
    }

    static List<Long> ids(ResponseEntity<Map<String, Object>> page) {
        return content(page).stream().map(d -> ((Number) d.get("id")).longValue()).toList();
    }

    @Test
    void anyoneSeesConfirmedDonationsNewestFirstWithTheTotal() {
        long id = liveBroadcast();
        ApiSession donor = ApiSession.member(http);
        long first = donate(donor, id, 1_000);
        long second = donate(donor, id, 5_000);

        ResponseEntity<Map<String, Object>> page = ApiSession.visitor(http).get(donationsOf(id));

        assertThat(ids(page)).containsExactly(second, first);
        assertThat(content(page).getFirst()).containsOnlyKeys("id", "donor", "amount", "message", "confirmedAt")
                .containsEntry("donor", ref(donor))
                .containsEntry("amount", 5_000)
                .containsEntry("message", "응원해요")
                .containsEntry("confirmedAt", FakePaymentGateway.APPROVED_AT.toString());
        assertThat(page.getBody())
                .containsEntry("total", 6_000)
                .containsEntry("nextBefore", null)
                .containsEntry("nextAfter", null);
    }

    @Test
    void onlyConfirmedDonationsAppearAndCount() {
        long id = liveBroadcast();
        ApiSession donor = ApiSession.member(http);
        order(donor, id, 2_000); // stays PENDING
        Map<String, Object> rejected = order(donor, id, 3_000);
        payments.nextConfirm(r -> { throw new PaymentRejectedException("REJECT_CARD_PAYMENT", "한도초과"); });
        assertThat(confirm(donor, approval(rejected, "tgen_rejected", 3_000)).getStatusCode())
                .isNotEqualTo(HttpStatus.OK); // FAILED
        Map<String, Object> reported = order(donor, id, 4_000);
        assertThat(donor.post("/api/donations/" + reported.get("orderId") + "/fail", Map.of("code", "USER_CANCEL"))
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT); // FAILED
        long confirmed = donate(donor, id, 10_000);

        ResponseEntity<Map<String, Object>> page = ApiSession.visitor(http).get(donationsOf(id));

        assertThat(ids(page)).containsExactly(confirmed);
        assertThat(page.getBody()).containsEntry("total", 10_000);
    }

    @Test
    void aBroadcastWithoutDonationsHasAnEmptyHistoryAndZeroTotal() {
        ResponseEntity<Map<String, Object>> page = ApiSession.visitor(http).get(donationsOf(liveBroadcast()));

        assertThat(ids(page)).isEmpty();
        assertThat(page.getBody()).containsEntry("total", 0);
    }

    @Test
    void donationsOfOneBroadcastDoNotShowOnAnother() {
        long id = liveBroadcast();
        donate(ApiSession.member(http), liveBroadcast(), 1_000);

        assertThat(ids(ApiSession.visitor(http).get(donationsOf(id)))).isEmpty();
    }

    @Test
    void unknownBroadcastIs404() {
        ResponseEntity<Map<String, Object>> page = ApiSession.visitor(http).get(donationsOf(999_999));

        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(page.getBody()).containsEntry("detail", "999999번 방송을 찾을 수 없습니다.");
    }

    @Test
    void beforePagesOlderDonationsAndTheTotalCoversAllOfThem() {
        long id = liveBroadcast();
        ApiSession donor = ApiSession.member(http);
        long d1 = donate(donor, id, 1_000);
        long d2 = donate(donor, id, 2_000);
        long d3 = donate(donor, id, 3_000);
        ApiSession visitor = ApiSession.visitor(http);

        ResponseEntity<Map<String, Object>> first = visitor.get(donationsOf(id) + "?size=2");
        assertThat(ids(first)).containsExactly(d3, d2);
        assertThat(first.getBody()).containsEntry("nextBefore", (int) d2).containsEntry("total", 6_000);

        ResponseEntity<Map<String, Object>> second = visitor.get(donationsOf(id) + "?size=2&before=" + d2);
        assertThat(ids(second)).containsExactly(d1);
        assertThat(second.getBody()).containsEntry("nextBefore", null).containsEntry("total", 6_000);
    }

    @Test
    void afterPagesNewerDonationsOldestPageFirstStillNewestFirstWithinAPage() {
        long id = liveBroadcast();
        ApiSession donor = ApiSession.member(http);
        long d1 = donate(donor, id, 1_000);
        long d2 = donate(donor, id, 2_000);
        long d3 = donate(donor, id, 3_000);
        long d4 = donate(donor, id, 4_000);
        ApiSession visitor = ApiSession.visitor(http);

        ResponseEntity<Map<String, Object>> first = visitor.get(donationsOf(id) + "?size=2&after=" + d1);
        assertThat(ids(first)).containsExactly(d3, d2);
        assertThat(first.getBody()).containsEntry("nextAfter", (int) d3).containsEntry("total", 10_000);

        ResponseEntity<Map<String, Object>> second = visitor.get(donationsOf(id) + "?size=2&after=" + d3);
        assertThat(ids(second)).containsExactly(d4);
        assertThat(second.getBody()).containsEntry("nextAfter", null);

        assertThat(ids(visitor.get(donationsOf(id) + "?after=" + d4))).isEmpty();
    }

    @Test
    void sizeDefaultsTo20AndBadSizeIs400() {
        long id = liveBroadcast();
        ApiSession donor = ApiSession.member(http);
        for (int i = 0; i < 21; i++) donate(donor, id, 1_000);

        ResponseEntity<Map<String, Object>> page = ApiSession.visitor(http).get(donationsOf(id));

        assertThat(ids(page)).hasSize(20);
        assertThat(page.getBody()).containsEntry("total", 21_000);
        assertThat(ApiSession.visitor(http).get(donationsOf(id) + "?size=0").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
