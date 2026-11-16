package com.chzzk.donation.web;

import com.chzzk.common.auth.MemberRef;
import com.chzzk.donation.domain.DonationOrder;
import com.chzzk.donation.domain.DonationService;
import com.chzzk.donation.domain.DonationStatus;
import com.chzzk.donation.domain.DonationView;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 후원 API (ADR-0004). {@code POST /api/broadcasts/{id}/donations} creates the order and hands the browser what the
 * Toss 결제창 needs (the client key only; the secret key stays here); {@code POST /api/donations/confirm} and
 * {@code POST /api/donations/{orderId}/fail} are called by the success and fail pages Toss redirects to.
 */
@RestController
class DonationController {

    static final String SUCCESS_PATH = "/donations/success";
    static final String FAIL_PATH = "/donations/fail";

    private final DonationService donations;
    private final String clientKey;

    DonationController(DonationService donations, @Value("${donation.toss.client-key}") String clientKey) {
        this.donations = donations;
        this.clientKey = clientKey;
    }

    @PostMapping("/api/broadcasts/{broadcastId}/donations")
    ResponseEntity<OrderResponse> start(@PathVariable long broadcastId, @RequestBody StartRequest request) {
        DonationOrder order = donations.start(broadcastId, request.amount(), request.message());
        String orderId = order.orderId().toString();
        return ResponseEntity.status(HttpStatus.CREATED).body(new OrderResponse(orderId, orderName(order.amount()),
                order.amount(), clientKey, SUCCESS_PATH,
                // Toss keeps our query and adds its own; on a cancel it sends no orderId, so ours tells the page.
                FAIL_PATH + "?orderId=" + orderId + "&broadcastId=" + broadcastId, order.recipient()));
    }

    @PostMapping("/api/donations/confirm")
    DonationResponse confirm(@RequestBody ConfirmRequest request) {
        return DonationResponse.of(donations.confirm(request.paymentKey(), request.orderId(), request.amount()));
    }

    @PostMapping("/api/donations/{orderId}/fail")
    ResponseEntity<Void> fail(@PathVariable String orderId, @RequestBody(required = false) FailRequest request) {
        FailRequest reason = request == null ? new FailRequest(null, null) : request;
        donations.fail(orderId, reason.code(), reason.message());
        return ResponseEntity.noContent().build();
    }

    /** e.g. "후원 5,000원" (Toss shows it in the 결제창; at most 100 characters). */
    static String orderName(int amount) {
        return "후원 " + NumberFormat.getIntegerInstance(Locale.KOREA).format(amount) + "원";
    }

    record StartRequest(Integer amount, String message) {}

    record OrderResponse(String orderId, String orderName, int amount, String clientKey, String successUrl,
                         String failUrl, MemberRef recipient) {}

    record ConfirmRequest(String paymentKey, String orderId, Long amount) {}

    record FailRequest(String code, String message) {}

    record DonationResponse(long id, String orderId, long broadcastId, int amount, String message,
                            DonationStatus status, MemberRef donor, MemberRef recipient, Instant confirmedAt) {
        static DonationResponse of(DonationView v) {
            return new DonationResponse(v.id(), v.orderId().toString(), v.broadcastId(), v.amount(), v.message(),
                    v.status(), v.donor(), v.recipient(), v.confirmedAt());
        }
    }
}
