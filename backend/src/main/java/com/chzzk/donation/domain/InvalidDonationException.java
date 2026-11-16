package com.chzzk.donation.domain;

/** The 후원 금액 or 후원 메시지 broke its rule; {@link #field()} is {@code amount} or {@code message}. Maps to {@code 400}. */
public class InvalidDonationException extends RuntimeException {

    private final String field;

    public InvalidDonationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
