package com.chzzk.donation.payment;

import java.util.Optional;

/**
 * The payment provider as the donations module sees it (ADR-0004): approve a payment the buyer authenticated in the
 * payment window, and look a payment up. Answers are normalized to {@link PaymentResult}; the caller verifies them
 * against its own order. Production uses {@link TossPaymentGateway}; API tests use a scriptable fake.
 *
 * <p>Failures: a definitive refusal by the provider throws {@link PaymentRejectedException} (with its code); not
 * reaching it, or an answer whose outcome is unknown, throws {@link PaymentUnavailableException}, so the order can be
 * retried or reconciled later.
 */
public interface PaymentGateway {

    /**
     * Approves the payment (Toss {@code POST /v1/payments/confirm}).
     *
     * @param amount         the amount to approve: always the order's own amount, never the client's
     * @param idempotencyKey the same key for every attempt on one order, so a retry never approves twice
     */
    PaymentResult confirm(String paymentKey, String orderId, long amount, String idempotencyKey);

    /** The payment as the provider has it now, or empty when it doesn't know it. */
    Optional<PaymentResult> lookup(String paymentKey);

    /** The payment for our order id as the provider has it now, or empty when there is none. */
    Optional<PaymentResult> lookupByOrderId(String orderId);
}
