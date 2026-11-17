package com.chzzk.broadcast.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * 방송 rows. The 방송 중 → 종료 transitions are conditional updates ({@code ... and status = LIVE}): the database
 * re-checks the condition under the row lock, so when "방송 종료" and the auto-end race, exactly one of them changes
 * the row (and sets {@code ended_at}) and the other sees 0 rows updated.
 */
interface BroadcastRepository extends JpaRepository<Broadcast, Long> {

    @EntityGraph(attributePaths = "owner")
    Page<Broadcast> findByStatus(BroadcastStatus status, Pageable pageable);

    @EntityGraph(attributePaths = "owner")
    Page<Broadcast> findByStatusAndOwnerId(BroadcastStatus status, long ownerId, Pageable pageable);

    /** A 회원's 방송 중 방송, if any (there is at most one). */
    Optional<Broadcast> findFirstByOwnerIdAndStatus(long ownerId, BroadcastStatus status);

    /** A new 송출 키 hash for a 방송 중 방송; 0 when it is not 방송 중 (any more). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Broadcast b set b.publishKeyHash = :hash where b.id = :id and b.status = :live")
    int replacePublishKeyIfLive(long id, String hash, BroadcastStatus live);

    /**
     * Whether the 방송 has received 후원 (a CONFIRMED order). Read straight from the {@code donation} table so the
     * broadcast module doesn't depend on the donations module; that couples it to the table and its status values.
     */
    @Query(value = "select exists (select 1 from donation where broadcast_id = :id and status = 'CONFIRMED')",
            nativeQuery = true)
    boolean hasConfirmedDonations(long id);

    /**
     * Removes the 방송's orders that never became 후원 (PENDING, FAILED, EXPIRED), which would otherwise hold the
     * {@code ON DELETE RESTRICT} FK. A row confirmed concurrently is skipped (PostgreSQL re-checks the condition
     * after waiting for its lock) and keeps that FK, so the 방송 delete then fails instead.
     */
    @Modifying
    @Query(value = "delete from donation where broadcast_id = :id and status <> 'CONFIRMED'", nativeQuery = true)
    int deleteUnconfirmedDonations(long id);

    @Modifying
    @Query("update Broadcast b set b.lastPublishingAt = :at where b.id in :ids and b.status = :live")
    int recordPublishing(Collection<Long> ids, BroadcastStatus live, Instant at);

    /** 방송 중 → 종료 at {@code at}; 0 when it was not 방송 중 (any more). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Broadcast b set b.status = :ended, b.endedAt = :at where b.id = :id and b.status = :live")
    int endIfLive(long id, Instant at, BroadcastStatus live, BroadcastStatus ended);

    /**
     * Ids of 방송 중 방송 whose media last arrived (or, never having published, that started) before {@code cutoff};
     * with {@code onlyNeverPublished}, just those that never published. In id order, so concurrent sweeps lock rows
     * in the same order.
     */
    @Query("""
            select b.id from Broadcast b
            where b.status = :live and coalesce(b.lastPublishingAt, b.startedAt) < :cutoff
              and (:onlyNeverPublished = false or b.lastPublishingAt is null)
            order by b.id""")
    List<Long> findLiveSilentSince(BroadcastStatus live, Instant cutoff, boolean onlyNeverPublished);

    /**
     * 방송 중 → 종료 with {@code ended_at} = when its media stopped (its last publishing time, or its start time), if
     * it is still 방송 중 and still silent since before {@code cutoff}; 0 otherwise.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Broadcast b set b.status = :ended, b.endedAt = coalesce(b.lastPublishingAt, b.startedAt)
            where b.id = :id and b.status = :live and coalesce(b.lastPublishingAt, b.startedAt) < :cutoff""")
    int endIfSilentSince(long id, Instant cutoff, BroadcastStatus live, BroadcastStatus ended);
}
