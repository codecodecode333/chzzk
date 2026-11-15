package com.chzzk.broadcast;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wiring for the 방송 feature. Tests replace the clock (and the media server gateway) with their own. */
@Configuration(proxyBeanMethods = false)
class BroadcastConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
