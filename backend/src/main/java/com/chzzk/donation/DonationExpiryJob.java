package com.chzzk.donation;

import com.chzzk.common.job.SingleInstanceJobs;
import com.chzzk.donation.domain.DonationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Moves 후원 orders PENDING for over {@link DonationService#EXPIRE_AFTER} to EXPIRED (time from the injected clock).
 * {@link DonationExpirySchedule} runs it every minute; API tests switch that off ({@code donation.expiry.enabled=false})
 * and call {@link #expire()} by hand.
 */
@Component
class DonationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(DonationExpiryJob.class);

    /** The job's lock key: one instance runs each round. */
    static final String JOB = "donation.expiry";

    private final DonationService donations;
    private final SingleInstanceJobs jobs;

    DonationExpiryJob(DonationService donations, SingleInstanceJobs jobs) {
        this.donations = donations;
        this.jobs = jobs;
    }

    void expire() {
        int expired = donations.expireStalePending();
        if (expired > 0) log.info("Expired {} 후원 orders PENDING for over {}", expired, DonationService.EXPIRE_AFTER);
    }
}
