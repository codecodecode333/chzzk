package com.chzzk.donation.payment;

/**
 * A payment's status at the provider (Toss's {@code status} values). Only {@link #DONE} means paid; {@link #isInFlight()}
 * tells a payment that may still become {@code DONE} from one that definitively won't.
 */
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

    /**
     * Whether the provider may still complete this payment: not yet authenticated or approved ({@code READY},
     * {@code IN_PROGRESS}), awaiting a deposit ({@code WAITING_FOR_DEPOSIT}), or a status this code can't read
     * ({@link #UNKNOWN}, never taken as a failure). {@code DONE} and the definitive non-successes ({@code ABORTED},
     * {@code EXPIRED}, {@code CANCELED}, {@code PARTIAL_CANCELED}) are not.
     */
    public boolean isInFlight() {
        return switch (this) {
            case READY, IN_PROGRESS, WAITING_FOR_DEPOSIT, UNKNOWN -> true;
            case DONE, CANCELED, PARTIAL_CANCELED, ABORTED, EXPIRED -> false;
        };
    }

    /** The status for a provider's value; {@link #UNKNOWN} for anything unrecognised. */
    public static PaymentStatus of(String value) {
        for (PaymentStatus status : values()) {
            if (status.name().equals(value)) return status;
        }
        return UNKNOWN;
    }
}
