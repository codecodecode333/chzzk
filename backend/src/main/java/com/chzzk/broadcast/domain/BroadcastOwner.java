package com.chzzk.broadcast.domain;

import com.chzzk.common.auth.MemberRef;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/**
 * A 방송's 소유자 as the broadcast module reads it: a read-only view of the {@code member} row, just its id and 닉네임.
 * The member module owns that table; this keeps 방송 queries to one join without depending on the member module
 * (the same model as the VOD module's {@code VodOwner}).
 */
@Entity
@Immutable
@Table(name = "member")
public class BroadcastOwner {

    @Id
    private Long id;

    @Column(nullable = false)
    private String nickname;

    protected BroadcastOwner() {}

    public long getId() { return id; }
    public String getNickname() { return nickname; }

    /** As other features and API responses see a 회원. */
    public MemberRef toRef() {
        return new MemberRef(id, nickname);
    }

    boolean is(MemberRef member) {
        return id == member.id();
    }
}
