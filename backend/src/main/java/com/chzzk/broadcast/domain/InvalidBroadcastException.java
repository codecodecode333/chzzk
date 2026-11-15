package com.chzzk.broadcast.domain;

/** Input that cannot start a 방송, naming the offending field. */
public class InvalidBroadcastException extends RuntimeException {

    private final String field;

    public InvalidBroadcastException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
