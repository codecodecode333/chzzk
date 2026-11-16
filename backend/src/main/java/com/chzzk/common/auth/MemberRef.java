package com.chzzk.common.auth;

import java.io.Serializable;
import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * A 회원 as other features see one: its id and 닉네임 (e.g. a VOD's 소유자, a 댓글's 작성자). Obtained only from
 * {@link CurrentMember}. It is also the logged-in principal kept in the session, hence {@link Serializable}.
 */
public record MemberRef(long id, String nickname) implements Serializable, AuthenticatedPrincipal {

    /** The principal name Spring Session indexes sessions by: the 회원 id. */
    @Override
    public String getName() {
        return Long.toString(id);
    }
}
