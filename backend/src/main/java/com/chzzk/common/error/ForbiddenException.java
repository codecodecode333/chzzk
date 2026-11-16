package com.chzzk.common.error;

/**
 * Logged in, but not allowed: not the 소유자 of the VOD/방송 or the 작성자 of the 댓글. Throw it from the domain
 * service that guards the data; every API answers {@code 403} Problem Details with this message.
 */
public class ForbiddenException extends RuntimeException {

    public static final String NOT_ALLOWED = "권한이 없습니다.";

    public ForbiddenException() {
        this(NOT_ALLOWED);
    }

    /** @param message user-facing, e.g. "내 VOD만 수정할 수 있습니다." */
    public ForbiddenException(String message) {
        super(message);
    }
}
