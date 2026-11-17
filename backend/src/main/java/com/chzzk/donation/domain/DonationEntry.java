package com.chzzk.donation.domain;

import com.chzzk.common.auth.MemberRef;
import java.time.Instant;

/** One confirmed 후원 as a 방송's 내역 shows it: who gave how much, with what message, when. */
public record DonationEntry(long id, MemberRef donor, int amount, String message, Instant confirmedAt) {

    /** For the repository's constructor expression, which can't build the nested {@link MemberRef}. */
    DonationEntry(long id, long donorId, String donorNickname, int amount, String message, Instant confirmedAt) {
        this(id, new MemberRef(donorId, donorNickname), amount, message, confirmedAt);
    }
}
