package com.chzzk.broadcast.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One 방송. Its state changes only through {@link BroadcastLifecycle}, which owns every status transition; this class
 * exposes reads to everyone else.
 */
@Entity
@Table(name = "broadcast")
public class Broadcast {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BroadcastStatus status;

    /** SHA-256 of the 송출 키 (see {@link PublishKeys}); the key itself is never stored. */
    @Column(name = "publish_key_hash", nullable = false)
    private String publishKeyHash;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "last_publishing_at")
    private Instant lastPublishingAt;

    protected Broadcast() {}

    Broadcast(String title, String publishKeyHash, Instant startedAt) {
        this.title = title;
        this.publishKeyHash = publishKeyHash;
        this.status = BroadcastStatus.LIVE;
        this.startedAt = startedAt;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public BroadcastStatus getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public Instant getLastPublishingAt() { return lastPublishingAt; }

    public boolean isLive() {
        return status == BroadcastStatus.LIVE;
    }

    String getPublishKeyHash() { return publishKeyHash; }

    /** 방송 중 → 종료; there is no way back. Ending an ended 방송 changes nothing. */
    void end(Instant endedAt) {
        if (!isLive()) return;
        this.status = BroadcastStatus.ENDED;
        this.endedAt = endedAt;
    }
}
