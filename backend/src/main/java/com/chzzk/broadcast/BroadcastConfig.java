package com.chzzk.broadcast;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Wiring for the 방송 feature. Tests replace the clock (and the media server gateway) with their own. */
@Configuration(proxyBeanMethods = false)
class BroadcastConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Runs the {@link PublishingMonitor} on its schedule; API tests switch this off and run its checks by hand. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "broadcast.publishing-monitor.enabled", matchIfMissing = true)
    static class PublishingMonitorSchedule {}
}
