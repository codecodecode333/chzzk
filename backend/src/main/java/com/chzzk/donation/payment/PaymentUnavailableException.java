package com.chzzk.donation.payment;

/**
 * The provider could not be reached, or answered without a definite outcome (timeout, 5xx, a concurrent duplicate
 * in flight). The payment may or may not have gone through: leave the order as it is and try again later.
 */
public class PaymentUnavailableException extends RuntimeException {

    public PaymentUnavailableException(String message) {
        super(message);
    }

    public PaymentUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
