package com.chzzk.donation.domain;

/** No such 후원 order, or no such 방송 to donate to. Maps to {@code 404}. */
public class DonationNotFoundException extends RuntimeException {

    public DonationNotFoundException(String message) {
        super(message);
    }
}
