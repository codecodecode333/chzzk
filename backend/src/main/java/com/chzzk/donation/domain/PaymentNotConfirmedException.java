package com.chzzk.donation.domain;

/**
 * The payment was not confirmed and the order is now FAILED: the client's amount differed from the order, or the
 * provider rejected the payment or reported something other than our order paid in full. {@link #code()} says why
 * (the provider's error code, or {@code AMOUNT_MISMATCH} / {@code PAYMENT_MISMATCH}). Maps to {@code 400}.
 */
public class PaymentNotConfirmedException extends RuntimeException {

    public static final String AMOUNT_MISMATCH = "AMOUNT_MISMATCH";
    public static final String PAYMENT_MISMATCH = "PAYMENT_MISMATCH";

    private final String code;

    public PaymentNotConfirmedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
