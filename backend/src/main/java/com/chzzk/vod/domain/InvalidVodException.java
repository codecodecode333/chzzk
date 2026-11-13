package com.chzzk.vod.domain;

/** Registration input broke a rule; {@link #field()} names the offending form field. Maps to {@code 400}. */
public class InvalidVodException extends RuntimeException {

    private final String field;

    public InvalidVodException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
