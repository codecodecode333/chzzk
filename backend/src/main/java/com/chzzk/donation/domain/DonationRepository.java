package com.chzzk.donation.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
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
    String OPEN = "(" + STATUS + "PENDING, " + STATUS + "EXPIRED)";

    Optional<Donation> findByOrderId(UUID orderId);

    @Query("select b from DonationBroadcast b where b.id = :id")
    Optional<DonationBroadcast> findBroadcast(long id);

    @Query("select m from DonationMember m where m.id = :id")
    Optional<DonationMember> findMember(long id);

    /** {@code PENDING|EXPIRED → CONFIRMED}, recording the provider's payment. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Donation d set d.status = " + STATUS + "CONFIRMED, d.paymentKey = :paymentKey,"
            + " d.confirmedAt = :confirmedAt, d.failureCode = null"
            + " where d.orderId = :orderId and d.status in " + OPEN)
    int markConfirmed(UUID orderId, String paymentKey, Instant confirmedAt);

    /** {@code PENDING|EXPIRED → FAILED}, with why. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Donation d set d.status = " + STATUS + "FAILED, d.failureCode = :failureCode"
            + " where d.orderId = :orderId and d.status in " + OPEN)
    int markFailed(UUID orderId, String failureCode);

    /** {@code PENDING → EXPIRED} for every order created before {@code cutoff}. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Donation d set d.status = " + STATUS + "EXPIRED"
            + " where d.status = " + STATUS + "PENDING and d.createdAt < :cutoff")
    int markExpired(Instant cutoff);
}
