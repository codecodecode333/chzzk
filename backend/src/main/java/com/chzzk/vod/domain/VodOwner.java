package com.chzzk.vod.domain;

import com.chzzk.common.auth.MemberRef;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/**
 * A VOD's 소유자 as the VOD module reads it: a read-only view of the {@code member} row, just its id and 닉네임.
 * The member module owns that table; this keeps VOD queries to one join without depending on the member module.
 */
@Entity
@Immutable
@Table(name = "member")
public class VodOwner {

    @Id
    private Long id;

    @Column(nullable = false)
    private String nickname;

    protected VodOwner() {}

    public long getId() { return id; }
    public String getNickname() { return nickname; }

    boolean is(MemberRef member) {
        return id == member.id();
    }
}
