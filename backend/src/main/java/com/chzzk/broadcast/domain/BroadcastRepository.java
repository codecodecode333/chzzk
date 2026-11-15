package com.chzzk.broadcast.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface BroadcastRepository extends JpaRepository<Broadcast, Long> {

    Page<Broadcast> findByStatus(BroadcastStatus status, Pageable pageable);
}
