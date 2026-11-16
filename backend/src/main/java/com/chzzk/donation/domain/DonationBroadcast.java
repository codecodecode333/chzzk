package com.chzzk.donation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/**
 * A 방송 as the donations module reads one: a read-only view of the {@code broadcast} row (its status and 소유자), so
 * this module checks who may be donated to without depending on the broadcast module.
 */
@Entity(name = "DonationBroadcast")
@Table(name = "broadcast")
@Immutable
class DonationBroadcast {

    @Id
    private Long id;

    @Column(nullable = false, length = 10)
    private String status;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private DonationMember owner;

    protected DonationBroadcast() {}

    long getId() { return id; }
    DonationMember getOwner() { return owner; }

    /** 방송 중 (the broadcast module's {@code LIVE}). */
    boolean isLive() {
        return "LIVE".equals(status);
    }
}
