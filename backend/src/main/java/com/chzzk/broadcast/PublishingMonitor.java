package com.chzzk.broadcast;

import com.chzzk.broadcast.domain.BroadcastLifecycle;
import com.chzzk.broadcast.media.MediaServerGateway;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The publishing monitor: every 10s it asks the media server which 방송 are receiving media, records that, and then
 * lets the lifecycle sweep away the 방송 whose media stopped (the 60-second auto-end). Liveness comes only from this
 * poll of the media server, never from its ready/not-ready hooks, so a missed event or a backend restart can't strand
 * a 방송 as 방송 중. While the media server can't be asked, nothing is ended.
 */
@Component
class PublishingMonitor {

    private static final Logger log = LoggerFactory.getLogger(PublishingMonitor.class);

    private final MediaServerGateway mediaServer;
    private final BroadcastLifecycle lifecycle;

    PublishingMonitor(MediaServerGateway mediaServer, BroadcastLifecycle lifecycle) {
        this.mediaServer = mediaServer;
        this.lifecycle = lifecycle;
    }

    @Scheduled(fixedDelayString = "${broadcast.publishing-monitor.interval:10s}",
            initialDelayString = "${broadcast.publishing-monitor.interval:10s}")
    void check() {
        Set<Long> publishing;
        try {
            publishing = mediaServer.listPublishingPaths();
        } catch (RuntimeException e) {
            // Without the media server's answer, silence can't be told from an outage, so nothing is ended this time.
            log.warn("Could not ask the media server who is publishing; skipping this auto-end sweep", e);
            return;
        }
        lifecycle.recordPublishing(publishing);
        List<Long> ended = lifecycle.sweep(lifecycle.now());
        if (!ended.isEmpty()) log.info("Auto-ended 방송 {}: no media for over 60 seconds", ended);
    }
}
