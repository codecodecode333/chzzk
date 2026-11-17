package com.chzzk.donation.web;

import com.chzzk.donation.domain.DonationService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Toss's payment-status webhook (ADR-0004). Toss calls it from the internet with no session or CSRF token, and a
 * {@code PAYMENT_STATUS_CHANGED} event carries no signature, so its body is only a hint: just {@code paymentKey} and
 * {@code orderId} are read, and {@link DonationService#reconcile} looks the payment up with Toss before anything
 * changes. Amounts and statuses in the body are ignored. Every event, including unknown orders and other event types,
 * is answered {@code 200} so Toss stops retrying; only Toss being unreachable for the lookup answers {@code 503}, so
 * Toss retries later. Under {@code /internal}, which the dev proxy doesn't forward: expose the backend itself to Toss.
 *
 * <p>Remaining abuse surface: anyone can call it, and each call can cost up to two Toss lookups (by
 * {@code paymentKey}, then by {@code orderId}), so a flood of fake events spends our Toss API quota and server time
 * (it can't change any order: only what Toss itself reports is applied). Caller-supplied values are logged at debug
 * level only, and sanitized. In production, put rate limiting and an allow-list of Toss's webhook IP addresses in
 * front of this path; neither is implemented here.
 */
@RestController
class PaymentWebhookController {

    static final String PATH = "/internal/payments/webhook";
    static final String PAYMENT_STATUS_CHANGED = "PAYMENT_STATUS_CHANGED";

    private final DonationService donations;

    PaymentWebhookController(DonationService donations) {
        this.donations = donations;
    }

    @PostMapping(PATH)
    ResponseEntity<Void> paymentStatusChanged(@RequestBody(required = false) JsonNode event) {
        if (event != null && PAYMENT_STATUS_CHANGED.equals(event.path("eventType").asText())) {
            JsonNode data = event.path("data");
            donations.reconcile(text(data, "paymentKey"), text(data, "orderId"));
        }
        return ResponseEntity.ok().build();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }
}
