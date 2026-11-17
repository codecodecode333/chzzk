package com.chzzk.donation;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Runs the {@link DonationExpiryJob} every {@code donation.expiry.interval} (after one interval) on a thread of its
 * own, so the sweep doesn't depend on any other feature turning Spring's scheduling on. Switched by
 * {@code donation.expiry.enabled} (on unless set to {@code false}); API tests switch it off and run the job by hand.
 */
@Component
@ConditionalOnProperty(name = "donation.expiry.enabled", matchIfMissing = true)
class DonationExpirySchedule implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DonationExpirySchedule.class);

    private final DonationExpiryJob job;
    private final Duration interval;
    private ScheduledExecutorService executor;

    DonationExpirySchedule(DonationExpiryJob job, @Value("${donation.expiry.interval:1m}") Duration interval) {
        this.job = job;
        this.interval = interval;
    }

    @Override
    public synchronized void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "donation-expiry");
            thread.setDaemon(true);
            return thread;
        });
        long millis = interval.toMillis();
        executor.scheduleWithFixedDelay(this::runOnce, millis, millis, TimeUnit.MILLISECONDS);
    }

    private void runOnce() {
        try {
            job.expire();
        } catch (RuntimeException e) {
            // a failed sweep (e.g. the database briefly down) must not stop the next ones
            log.warn("후원 expiry sweep failed; retrying in {}", interval, e);
        }
    }

    @Override
    public synchronized void stop() {
        if (executor != null) executor.shutdownNow();
        executor = null;
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }
}
