package com.chzzk.common.error;

/** Not logged in (or bad credentials). Every API answers {@code 401} Problem Details with this message. */
public class UnauthenticatedException extends RuntimeException {

    public static final String LOGIN_REQUIRED = "로그인이 필요합니다.";

    public UnauthenticatedException() {
        this(LOGIN_REQUIRED);
    }

    public UnauthenticatedException(String message) {
        super(message);
    }
}
