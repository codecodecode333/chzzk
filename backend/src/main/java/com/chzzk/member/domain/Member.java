package com.chzzk.member.domain;

import com.chzzk.common.auth.MemberRef;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A 회원: logs in with an email and password, is shown to others by a 닉네임. */
@Entity
@Table(name = "member")
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Trimmed and lower-cased. */
    @Column(nullable = false, length = 254)
    private String email;

    /** BCrypt; the password itself is never stored. */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false, length = 20)
    private String nickname;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Member() {}

    Member(String email, String passwordHash, String nickname, Instant createdAt) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    String getPasswordHash() { return passwordHash; }
    public String getNickname() { return nickname; }
    public Instant getCreatedAt() { return createdAt; }

    /** This 회원 as other features see one. */
    public MemberRef toRef() {
        return new MemberRef(id, nickname);
    }
}
