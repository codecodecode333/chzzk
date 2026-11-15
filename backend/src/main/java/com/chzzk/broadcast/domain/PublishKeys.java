package com.chzzk.broadcast.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** 송출 키: 32 random bytes as unpadded base64url, stored only as a SHA-256 hash and compared in constant time. */
final class PublishKeys {

    private static final int KEY_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PublishKeys() {
    }

    static String generate() {
        byte[] key = new byte[KEY_BYTES];
        RANDOM.nextBytes(key);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key);
    }

    /** Lower-case hex SHA-256 of the key's UTF-8 bytes. */
    static String hash(String key) {
        return HexFormat.of().formatHex(sha256(key));
    }

    /** Whether {@code candidate} is the key behind {@code storedHash}, without leaking timing on the comparison. */
    static boolean matches(String candidate, String storedHash) {
        if (candidate == null || candidate.isEmpty()) return false;
        byte[] expected;
        try {
            expected = HexFormat.of().parseHex(storedHash);
        } catch (IllegalArgumentException corrupt) {
            return false;
        }
        return MessageDigest.isEqual(sha256(candidate), expected);
    }

    private static byte[] sha256(String key) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every Java platform", e);
        }
    }
}
