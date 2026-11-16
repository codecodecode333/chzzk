package com.chzzk.donation.payment;

/** The provider refused the request for good (e.g. {@code REJECT_CARD_PAYMENT}); {@link #code()} is its error code. */
public class PaymentRejectedException extends RuntimeException {

    private final String code;

    /** @param message the provider's user-facing message */
    public PaymentRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
