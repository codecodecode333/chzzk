package com.chzzk.broadcast;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the real clock and MediaMTX with test doubles the API tests control. */
@TestConfiguration(proxyBeanMethods = false)
public class BroadcastTestConfig {

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock();
    }

    @Bean
    @Primary
    FakeMediaServerGateway fakeMediaServerGateway() {
        return new FakeMediaServerGateway();
    }
}
