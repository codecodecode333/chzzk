package com.chzzk.donation.domain;

import com.chzzk.common.auth.MemberRef;
import java.util.UUID;

/** A freshly created PENDING 후원 order: what was fixed before payment. */
public record DonationOrder(UUID orderId, long broadcastId, int amount, MemberRef recipient) {}
