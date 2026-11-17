package com.chzzk.donation.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 후원 runs in Toss test mode only: the server refuses to start with keys that aren't test keys, unless overridden. */
class TossKeysTest {

    @Test
    void testKeysAreAccepted() {
        TossKeys keys = new TossKeys("test_ck_abc", "test_sk_def", false);

        assertThat(keys.clientKey()).isEqualTo("test_ck_abc");
        assertThat(keys.secretKey()).isEqualTo("test_sk_def");
    }

    @Test
    void liveOrMissingKeysAreRefused() {
        assertThatThrownBy(() -> new TossKeys("live_ck_abc", "test_sk_def", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TOSS_CLIENT_KEY")
                .hasMessageContaining("test_");
        assertThatThrownBy(() -> new TossKeys("test_ck_abc", "live_sk_def", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TOSS_SECRET_KEY");
        assertThatThrownBy(() -> new TossKeys("", "", false)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anExplicitOverrideAllowsOtherKeys() {
        TossKeys keys = new TossKeys("live_ck_abc", "live_sk_def", true);

        assertThat(keys.secretKey()).isEqualTo("live_sk_def");
    }
}
