package com.chzzk.broadcast.domain;

import com.chzzk.common.auth.MemberRef;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One 방송. Its state changes only through {@link BroadcastLifecycle}, which makes every status transition with a
 * conditional update in the database (so concurrent ones can't overwrite each other); this class exposes reads
 * and the title change.
 */
@Entity
@Table(name = "broadcast")
public class Broadcast {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String title;

    /** The 소유자, who is also its 송출자. Loaded with the 방송 so its 닉네임 can always be shown. */
    @ManyToOne(optional = false)
    @JoinColumn(name = "owner_id", nullable = false, updatable = false)
    private BroadcastOwner owner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BroadcastStatus status;

    /** SHA-256 of the 송출 키 (see {@link PublishKey}); the key itself is never stored. */
    @Column(name = "publish_key_hash", nullable = false)
    private String publishKeyHash;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "last_publishing_at")
    private Instant lastPublishingAt;

    protected Broadcast() {}

    Broadcast(BroadcastOwner owner, String title, String publishKeyHash, Instant startedAt) {
        this.owner = owner;
        this.title = title;
        this.publishKeyHash = publishKeyHash;
        this.status = BroadcastStatus.LIVE;
        this.startedAt = startedAt;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public BroadcastOwner getOwner() { return owner; }
    public BroadcastStatus getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public Instant getLastPublishingAt() { return lastPublishingAt; }

    public boolean isLive() {
        return status == BroadcastStatus.LIVE;
    }

    void rename(String title) {
        this.title = title;
    }

    boolean isOwnedBy(MemberRef member) {
        return owner.is(member);
    }

    String getPublishKeyHash() { return publishKeyHash; }
}
