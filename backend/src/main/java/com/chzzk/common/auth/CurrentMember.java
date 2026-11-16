package com.chzzk.common.auth;

import com.chzzk.common.error.UnauthenticatedException;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Who is making the current request: the one way features learn the logged-in 회원, so none touches the security
 * context. Inject it into a domain service (or controller) and call {@link #require()} where login is needed: it
 * throws {@link UnauthenticatedException}, which every API answers with {@code 401}.
 */
@Component
public class CurrentMember {

    /** The logged-in 회원, or empty for a visitor. */
    public Optional<MemberRef> find() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof MemberRef member) {
            return Optional.of(member);
        }
        return Optional.empty();
    }

    /** The logged-in 회원; a visitor gets {@code 401} ("로그인이 필요합니다."). */
    public MemberRef require() {
        return find().orElseThrow(UnauthenticatedException::new);
    }
}
