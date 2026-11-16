package com.chzzk.common.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The request conflicts with the current state (a taken email or 닉네임, a second 방송 중 방송, deleting a 방송 중
 * 방송). Every API answers {@code 409} Problem Details with this message, plus {@code errors: [{field, message}]} when
 * a form field is named and any extra {@link #properties()} (e.g. the id of the existing 방송).
 */
public class ConflictException extends RuntimeException {

    private final String field;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public ConflictException(String message) {
        this(null, message);
    }

    private ConflictException(String field, String message) {
        super(message);
        this.field = field;
    }

    /** A conflict caused by one form field, e.g. {@code onField("email", "이미 가입된 이메일입니다.")}. */
    public static ConflictException onField(String field, String message) {
        return new ConflictException(field, message);
    }

    /** Adds a property to the Problem body, e.g. {@code with("broadcastId", 12)}. */
    public ConflictException with(String name, Object value) {
        properties.put(name, value);
        return this;
    }

    /** The form field at fault, or {@code null}. */
    public String field() {
        return field;
    }

    public Map<String, Object> properties() {
        return Collections.unmodifiableMap(properties);
    }
}
