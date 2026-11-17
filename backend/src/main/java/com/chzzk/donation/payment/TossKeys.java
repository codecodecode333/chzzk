package com.chzzk.donation.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The Toss API keys ({@code donation.toss.client-key}/{@code secret-key}, from {@code TOSS_CLIENT_KEY}/
 * {@code TOSS_SECRET_KEY}). 후원 runs in Toss <b>test mode only</b> (ADR-0004): unless
 * {@code donation.toss.allow-non-test-keys} ({@code TOSS_ALLOW_NON_TEST_KEYS}) is explicitly {@code true}, the server
 * refuses to start when either key doesn't start with {@code test_}, so a live key can't move real money by accident.
 * The client key goes to the browser; the secret key never leaves the server.
 */
@Component
public class TossKeys {

    static final String TEST_PREFIX = "test_";

    private final String clientKey;
    private final String secretKey;

    TossKeys(@Value("${donation.toss.client-key}") String clientKey,
             @Value("${donation.toss.secret-key}") String secretKey,
             @Value("${donation.toss.allow-non-test-keys:false}") boolean allowNonTestKeys) {
        if (!allowNonTestKeys) {
            requireTestKey("TOSS_CLIENT_KEY", clientKey);
            requireTestKey("TOSS_SECRET_KEY", secretKey);
        }
        this.clientKey = clientKey;
        this.secretKey = secretKey;
    }

    private static void requireTestKey(String name, String key) {
        if (key == null || !key.startsWith(TEST_PREFIX)) {
            throw new IllegalStateException(name + " is not a Toss test key (it must start with \"" + TEST_PREFIX
                    + "\"): 후원 runs in Toss test mode only. Set your own test_ck_/test_sk_ keys (see README, 후원),"
                    + " or set TOSS_ALLOW_NON_TEST_KEYS=true if you really mean to use other keys.");
        }
    }

    /** The key the browser opens the Toss 결제창 with. */
    public String clientKey() {
        return clientKey;
    }

    String secretKey() {
        return secretKey;
    }
}
