package com.chzzk.donation.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/**
 * 후원 order storage. Status transitions are conditional single-statement updates: each returns how many rows changed
 * (0 or 1), so of several concurrent callers exactly one wins and the others re-read the current state.
 */
interface DonationRepository extends JpaRepository<Donation, Long> {

    String STATUS = "com.chzzk.donation.domain.DonationStatus.";

    Optional<Donation> findByOrderId(UUID orderId);

    @Query("select b from DonationBroadcast b where b.id = :id")
    Optional<DonationBroadcast> findBroadcast(long id);

    @Query("select m from DonationMember m where m.id = :id")
    Optional<DonationMember> findMember(long id);

    /**
     * {@code PENDING|EXPIRED → CONFIRMED}, recording the provider's payment and taking the next confirmation sequence
     * number. Call it inside {@link #lockConfirmationsOf}'s transaction, so the 방송's 후원 commit in sequence order.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "update donation set status = 'CONFIRMED', payment_key = :paymentKey, confirmed_at = :confirmedAt,"
            + " failure_code = null, confirmed_seq = nextval('donation_confirmed_seq')"
            + " where order_id = :orderId and is_open", nativeQuery = true)
    int markConfirmed(UUID orderId, String paymentKey, Instant confirmedAt);

    /** The advisory lock namespace (first key) of {@link #lockConfirmationsOf}: "don" in ASCII. */
    int CONFIRMATION_LOCK = 0x646F6E;

    /**
     * Holds the 방송's confirmation lock until the current transaction ends (a PostgreSQL transaction-level advisory
     * lock, namespaced by {@link #CONFIRMATION_LOCK}). Confirmations of one 방송 then commit in the order they took
     * their sequence numbers, so a reader that has seen number {@code n} never later sees a smaller one appear.
     */
    @Query(value = "select count(*) from (select pg_advisory_xact_lock(" + CONFIRMATION_LOCK + ","
            + " cast(:broadcastId % 2147483647 as int))) locked", nativeQuery = true)
    long lockConfirmationsOf(long broadcastId);

    /** {@code PENDING|EXPIRED → FAILED}, with why. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Donation d set d.status = " + STATUS + "FAILED, d.failureCode = :failureCode"
            + " where d.orderId = :orderId and d.open = true")
    int markFailed(UUID orderId, String failureCode);

    /** {@code PENDING → EXPIRED} for every order created before {@code cutoff}. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Donation d set d.status = " + STATUS + "EXPIRED"
            + " where d.status = " + STATUS + "PENDING and d.createdAt < :cutoff")
    int markExpired(Instant cutoff);

    // ---- 내역 and 합계 (CONFIRMED only) ----

    String ENTRY = "select new com.chzzk.donation.domain.DonationEntry(d.id, d.confirmedSeq, m.id, m.nickname,"
            + " d.amount, d.message, d.confirmedAt) from Donation d join DonationMember m on m.id = d.donorId"
            + " where d.broadcastId = :broadcastId and d.status = " + STATUS + "CONFIRMED ";

    @Query(ENTRY + "and d.confirmedSeq < :before order by d.confirmedSeq desc")
    List<DonationEntry> findConfirmed(long broadcastId, long before, Limit limit);

    @Query(ENTRY + "and d.confirmedSeq > :after order by d.confirmedSeq asc")
    List<DonationEntry> findConfirmedAfter(long broadcastId, long after, Limit limit);

    @Query("select coalesce(sum(d.amount), 0) from Donation d"
            + " where d.broadcastId = :broadcastId and d.status = " + STATUS + "CONFIRMED")
    long totalConfirmed(long broadcastId);
}
