package com.chzzk.donation.domain;

import com.chzzk.common.auth.MemberRef;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/**
 * A 후원자 or recipient as the donations module reads one: a read-only view of the {@code member} row (id and 닉네임),
 * so this module joins 닉네임 without depending on the member module.
 */
@Entity(name = "DonationMember")
@Table(name = "member")
@Immutable
class DonationMember {

    @Id
    private Long id;

    @Column(nullable = false, length = 20)
    private String nickname;

    protected DonationMember() {}

    MemberRef toRef() {
        return new MemberRef(id, nickname);
    }
}
