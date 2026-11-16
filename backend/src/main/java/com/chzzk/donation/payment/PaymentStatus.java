package com.chzzk.donation.payment;

/** A payment's status at the provider (Toss's {@code status} values). Only {@link #DONE} means paid. */
public enum PaymentStatus {
    READY,
    IN_PROGRESS,
    WAITING_FOR_DEPOSIT,
    DONE,
    CANCELED,
    PARTIAL_CANCELED,
    ABORTED,
    EXPIRED,
    /** A status this code doesn't know (Toss may add some); never treated as paid. */
    UNKNOWN;

    /** The status for a provider's value; {@link #UNKNOWN} for anything unrecognised. */
    public static PaymentStatus of(String value) {
        for (PaymentStatus status : values()) {
            if (status.name().equals(value)) return status;
        }
        return UNKNOWN;
    }
}
