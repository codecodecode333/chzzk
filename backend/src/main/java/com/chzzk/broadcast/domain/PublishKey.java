package com.chzzk.broadcast.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 송출 키: 32 random bytes as unpadded base64url, handed out once at "방송 시작". Only its SHA-256 hash is stored, and a
 * presented key is compared against it in constant time. {@link #toString()} never reveals the key.
 */
public final class PublishKey {

    private static final int KEY_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String value;

    private PublishKey(String value) {
        this.value = value;
    }

    static PublishKey generate() {
        byte[] key = new byte[KEY_BYTES];
        RANDOM.nextBytes(key);
        return new PublishKey(Base64.getUrlEncoder().withoutPadding().encodeToString(key));
    }

    /** A 송출 키 as a client presented it. A missing ({@code null}) or empty key matches no 방송. */
    public static PublishKey presented(String value) {
        return new PublishKey(value == null ? "" : value);
    }

    /** The key itself, for the one response that hands it to the 송출자. */
    public String value() {
        return value;
    }

    /** Lower-case hex SHA-256 of the key's UTF-8 bytes: what the database keeps. */
    String hash() {
        return HexFormat.of().formatHex(sha256(value));
    }

    /** Whether this is the key behind {@code storedHash}, without leaking timing on the comparison. */
    boolean matches(String storedHash) {
        if (value.isEmpty()) return false;
        byte[] expected;
        try {
            expected = HexFormat.of().parseHex(storedHash);
        } catch (IllegalArgumentException corrupt) {
            return false;
        }
        return MessageDigest.isEqual(sha256(value), expected);
    }

    private static byte[] sha256(String key) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every Java platform", e);
        }
    }

    @Override
    public String toString() {
        return "PublishKey[redacted]";
    }
}
