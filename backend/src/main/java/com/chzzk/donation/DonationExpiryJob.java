package com.chzzk.donation;

import com.chzzk.common.job.JobLeases;
import com.chzzk.donation.domain.DonationService;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Moves 후원 orders PENDING for over {@link DonationService#EXPIRE_AFTER} to EXPIRED (time from the injected clock).
 * {@link DonationExpirySchedule} runs it every minute; API tests switch that off ({@code donation.expiry.enabled=false})
 * and call {@link #expire()} by hand. With several backend instances the sweep runs about once per interval across
 * all of them ({@link JobLeases}).
 */
@Component
class DonationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(DonationExpiryJob.class);

    /** The job's lease name: one instance runs each round. */
    static final String JOB = "donation.expiry";

    private final DonationService donations;
    private final JobLeases jobs;
    private final Duration interval;

    DonationExpiryJob(DonationService donations, JobLeases jobs,
            @Value("${donation.expiry.interval:1m}") Duration interval) {
        this.donations = donations;
        this.jobs = jobs;
        this.interval = interval;
    }

    /** One round of the sweep; skipped when another instance already started a round within the interval. */
    void expire() {
        jobs.runIfClaimed(JOB, interval, () -> {
            int expired = donations.expireStalePending();
            if (expired > 0) log.info("Expired {} 후원 orders PENDING for over {}", expired, DonationService.EXPIRE_AFTER);
        });
    }
}
