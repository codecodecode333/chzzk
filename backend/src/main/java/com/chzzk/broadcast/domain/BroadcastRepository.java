package com.chzzk.broadcast.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface BroadcastRepository extends JpaRepository<Broadcast, Long> {

    Page<Broadcast> findByStatus(BroadcastStatus status, Pageable pageable);

    @Modifying
    @Query("update Broadcast b set b.lastPublishingAt = :at where b.id in :ids and b.status = :live")
    int recordPublishing(Collection<Long> ids, BroadcastStatus live, Instant at);

    /** 방송 중 방송 whose media last arrived (or, never having published, that started) before {@code cutoff}. */
    @Query("select b from Broadcast b where b.status = :live and coalesce(b.lastPublishingAt, b.startedAt) < :cutoff")
    List<Broadcast> findLiveSilentSince(BroadcastStatus live, Instant cutoff);
}
