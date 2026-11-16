package com.chzzk.donation.domain;

/**
 * A 후원 order's state: {@code PENDING → CONFIRMED | FAILED | EXPIRED}, and {@code EXPIRED → CONFIRMED} when the
 * payment provider shows it paid after all. {@code CONFIRMED} and {@code FAILED} are final. Only {@code CONFIRMED}
 * orders are 후원 (listed and counted).
 */
public enum DonationStatus {
    PENDING,
    CONFIRMED,
    FAILED,
    EXPIRED
}
