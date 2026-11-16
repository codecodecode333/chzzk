package com.chzzk.donation.payment;

import java.time.Instant;

/**
 * A payment as the provider reports it, normalized.
 *
 * @param totalAmount the amount the provider approved (Toss {@code totalAmount})
 * @param approvedAt  when it was approved; {@code null} unless {@link PaymentStatus#DONE}
 */
public record PaymentResult(PaymentStatus status, long totalAmount, String orderId, String paymentKey,
                            Instant approvedAt) {

    public PaymentResult withStatus(PaymentStatus status) {
        return new PaymentResult(status, totalAmount, orderId, paymentKey, approvedAt);
    }

    public PaymentResult withTotalAmount(long totalAmount) {
        return new PaymentResult(status, totalAmount, orderId, paymentKey, approvedAt);
    }

    public PaymentResult withOrderId(String orderId) {
        return new PaymentResult(status, totalAmount, orderId, paymentKey, approvedAt);
    }
}
