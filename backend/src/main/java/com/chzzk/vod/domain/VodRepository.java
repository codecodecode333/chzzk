package com.chzzk.vod.domain;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

interface VodRepository extends JpaRepository<Vod, Long> {

    /** With the 소유자 joined in, so a page of VODs is one query. */
    @Override
    @EntityGraph(attributePaths = "owner")
    Page<Vod> findAll(Pageable pageable);

    @Override
    @EntityGraph(attributePaths = "owner")
    Optional<Vod> findById(Long id);
}
