package com.chzzk.donation.web;

import com.chzzk.common.auth.MemberRef;
import com.chzzk.common.web.Paging;
import com.chzzk.donation.domain.DonationHistory;
import com.chzzk.donation.domain.DonationHistoryPage;
import com.chzzk.donation.domain.DonationOrder;
import com.chzzk.donation.domain.DonationService;
import com.chzzk.donation.domain.DonationView;
import com.chzzk.donation.payment.TossKeys;
import java.text.NumberFormat;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 후원 API (ADR-0004). {@code POST /api/broadcasts/{id}/donations} creates the order and hands the browser what the
 * Toss 결제창 needs (the client key only; the secret key stays here); {@code POST /api/donations/confirm} and
 * {@code POST /api/donations/{orderId}/fail} are called by the success and fail pages Toss redirects to.
 * {@code GET /api/broadcasts/{id}/donations} is the 방송's public 내역 and 합계, paged like 댓글. The domain's
 * {@code DonationView} and {@code DonationHistoryPage} are serialized as they are: they already are the API shape.
 */
@RestController
class DonationController {

    static final String SUCCESS_PATH = "/donations/success";
    static final String FAIL_PATH = "/donations/fail";

    private final DonationService donations;
    private final DonationHistory history;
    private final String clientKey;
    /** Prefixed to the success/fail paths: the public site's address in prod, blank (relative paths) in dev. */
    private final String siteBaseUrl;

    DonationController(DonationService donations, DonationHistory history, TossKeys keys,
                       @Value("${site.public-base-url:}") String siteBaseUrl) {
        this.donations = donations;
        this.history = history;
        this.clientKey = keys.clientKey();
        this.siteBaseUrl = siteBaseUrl.strip().replaceAll("/+$", "");
    }

    @GetMapping("/api/broadcasts/{broadcastId}/donations")
    DonationHistoryPage list(@PathVariable long broadcastId, @RequestParam(required = false) Long before,
                         @RequestParam(required = false) Long after,
                         @RequestParam(defaultValue = "" + Paging.DEFAULT_PAGE_SIZE) int size) {
        int pageSize = Paging.of(0, size).size();
        return after != null // polling for newer 후원; before is then ignored
                ? history.listAfter(broadcastId, after, pageSize)
                : history.list(broadcastId, before, pageSize);
    }

    @PostMapping("/api/broadcasts/{broadcastId}/donations")
    ResponseEntity<OrderResponse> start(@PathVariable long broadcastId, @RequestBody StartRequest request) {
        DonationOrder order = donations.start(broadcastId, request.amount(), request.message());
        String orderId = order.orderId().toString();
        return ResponseEntity.status(HttpStatus.CREATED).body(new OrderResponse(orderId, orderName(order.amount()),
                order.amount(), clientKey, siteBaseUrl + SUCCESS_PATH,
                // Toss keeps our query and adds its own; on a cancel it sends no orderId, so ours tells the page.
                siteBaseUrl + FAIL_PATH + "?orderId=" + orderId + "&broadcastId=" + broadcastId, order.recipient()));
    }

    @PostMapping("/api/donations/confirm")
    DonationView confirm(@RequestBody ConfirmRequest request) {
        return donations.confirm(request.paymentKey(), request.orderId(), request.amount());
    }

    @PostMapping("/api/donations/{orderId}/fail")
    ResponseEntity<Void> fail(@PathVariable String orderId, @RequestBody(required = false) FailRequest request) {
        FailRequest reason = request == null ? new FailRequest(null, null) : request;
        donations.fail(orderId, reason.code(), reason.message());
        return ResponseEntity.noContent().build();
    }

    /** e.g. "후원 5,000원" (Toss shows it in the 결제창; at most 100 characters). */
    static String orderName(long amount) {
        return "후원 " + NumberFormat.getIntegerInstance(Locale.KOREA).format(amount) + "원";
    }

    record StartRequest(Long amount, String message) {}

    record OrderResponse(String orderId, String orderName, long amount, String clientKey, String successUrl,
                         String failUrl, MemberRef recipient) {}

    record ConfirmRequest(String paymentKey, String orderId, Long amount) {}

    record FailRequest(String code, String message) {}
}
