package com.chzzk.donation.domain;

import com.chzzk.common.auth.MemberRef;
import java.time.Instant;
import java.util.UUID;

/** A 후원 order as the API shows it (serialized as is), with its 후원자 and recipient. */
public record DonationView(long id, UUID orderId, long broadcastId, long amount, String message,
                           DonationStatus status, MemberRef donor, MemberRef recipient, Instant confirmedAt) {

    static DonationView of(Donation d, MemberRef donor, MemberRef recipient) {
        return new DonationView(d.getId(), d.getOrderId(), d.getBroadcastId(), d.getAmount(), d.getMessage(),
                d.getStatus(), donor, recipient, d.getConfirmedAt());
    }
}
