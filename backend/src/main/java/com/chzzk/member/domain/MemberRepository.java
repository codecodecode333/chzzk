package com.chzzk.member.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface MemberRepository extends JpaRepository<Member, Long> {

    /** {@code email} must already be normalized (trimmed, lower-cased). */
    Optional<Member> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByNickname(String nickname);
}
