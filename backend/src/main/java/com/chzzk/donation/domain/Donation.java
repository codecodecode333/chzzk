package com.chzzk.donation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One 후원 order (ADR-0004): created {@link DonationStatus#PENDING} before payment with its amount and recipient fixed,
 * and a 후원 only once {@link DonationStatus#CONFIRMED}. Its status changes only through {@link DonationService}, by
 * conditional updates in the database (so concurrent confirmations can't both win); this class exposes reads only.
 */
@Entity
@Table(name = "donation")
public class Donation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "broadcast_id", nullable = false, updatable = false)
    private long broadcastId;

    @Column(name = "donor_id", nullable = false, updatable = false)
    private long donorId;

    @Column(name = "recipient_id", nullable = false, updatable = false)
    private long recipientId;

    @Column(nullable = false, updatable = false)
    private long amount;

    @Column(length = 100, updatable = false)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DonationStatus status;

    @Column(name = "payment_key")
    private String paymentKey;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    /** {@link DonationStatus#isOpen()} as the database computes it (a generated column), for queries. */
    @Column(name = "is_open", insertable = false, updatable = false)
    private boolean open;

    /** The 후원's place in confirmation order (from a database sequence, set when it is confirmed); null until then. */
    @Column(name = "confirmed_seq", insertable = false, updatable = false)
    private Long confirmedSeq;

    protected Donation() {}

    Donation(UUID orderId, long broadcastId, long donorId, long recipientId, long amount, String message,
             Instant createdAt) {
        this.orderId = orderId;
        this.broadcastId = broadcastId;
        this.donorId = donorId;
        this.recipientId = recipientId;
        this.amount = amount;
        this.message = message;
        this.status = DonationStatus.PENDING;
        this.createdAt = createdAt;
    }

    public long getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public long getBroadcastId() { return broadcastId; }
    public long getDonorId() { return donorId; }
    public long getRecipientId() { return recipientId; }
    public long getAmount() { return amount; }
    public String getMessage() { return message; }
    public DonationStatus getStatus() { return status; }
    public String getPaymentKey() { return paymentKey; }
    public String getFailureCode() { return failureCode; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
}
