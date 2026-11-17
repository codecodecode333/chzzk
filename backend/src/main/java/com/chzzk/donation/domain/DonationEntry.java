package com.chzzk.donation.domain;

import com.chzzk.common.auth.MemberRef;
import java.time.Instant;

/**
 * One confirmed 후원 as a 방송's 내역 shows it: who gave how much, with what message, when. {@code seq} is its place
 * in confirmation order (increasing, with gaps), the 내역's sort key and paging cursor.
 */
public record DonationEntry(long id, long seq, MemberRef donor, long amount, String message, Instant confirmedAt) {

    /** For the repository's constructor expression, which can't build the nested {@link MemberRef}. */
    DonationEntry(long id, long seq, long donorId, String donorNickname, long amount, String message,
                  Instant confirmedAt) {
        this(id, seq, new MemberRef(donorId, donorNickname), amount, message, confirmedAt);
    }
}
