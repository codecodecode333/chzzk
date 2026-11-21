package com.chzzk.broadcast;

import com.chzzk.broadcast.domain.BroadcastLifecycle;
import com.chzzk.broadcast.media.MediaServerGateway;
import com.chzzk.common.job.SingleInstanceJobs;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The publishing monitor: every 10s it asks the media server which 방송 are receiving media, records that, and then
 * lets the lifecycle sweep away the 방송 whose media stopped (the auto-end after {@link BroadcastLifecycle#AUTO_END_AFTER}).
 * Liveness comes only from this poll of the media server, never from its ready/not-ready hooks, so a missed event or a
 * backend restart can't strand a 방송 as 방송 중. While the media server can't be asked, only 방송 that never sent
 * any media are ended: for the others, silence can't be told from the outage.
 */
@Component
class PublishingMonitor {

    private static final Logger log = LoggerFactory.getLogger(PublishingMonitor.class);

    /** The job's lock key: one instance runs each round. */
    static final String JOB = "broadcast.publishing-monitor";

    private final MediaServerGateway mediaServer;
    private final BroadcastLifecycle lifecycle;
    private final SingleInstanceJobs jobs;

    PublishingMonitor(MediaServerGateway mediaServer, BroadcastLifecycle lifecycle, SingleInstanceJobs jobs) {
        this.mediaServer = mediaServer;
        this.lifecycle = lifecycle;
        this.jobs = jobs;
    }

    @Scheduled(fixedDelayString = "${broadcast.publishing-monitor.interval:10s}",
            initialDelayString = "${broadcast.publishing-monitor.interval:10s}")
    void check() {
        Set<Long> publishing;
        try {
            publishing = mediaServer.listPublishingPaths();
        } catch (RuntimeException e) {
            log.warn("Could not ask the media server who is publishing; auto-ending only 방송 that never published", e);
            logEnded(lifecycle.sweepNeverPublished(lifecycle.now()));
            return;
        }
        lifecycle.recordPublishing(publishing);
        logEnded(lifecycle.sweep(lifecycle.now()));
    }

    private static void logEnded(List<Long> ended) {
        if (!ended.isEmpty()) {
            log.info("Auto-ended 방송 {}: no media for over {} seconds", ended,
                    BroadcastLifecycle.AUTO_END_AFTER.toSeconds());
        }
    }
}
