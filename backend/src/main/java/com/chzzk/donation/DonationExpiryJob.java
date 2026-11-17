package com.chzzk.donation;

import com.chzzk.donation.domain.DonationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every minute, moves 후원 orders PENDING for over {@link DonationService#EXPIRE_AFTER} to EXPIRED (time from the
 * injected clock). It runs on the application's scheduler, which {@code BroadcastConfig} enables; API tests that
 * switch scheduling off ({@code broadcast.publishing-monitor.enabled=false}) call {@link #expire()} by hand.
 */
@Component
class DonationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(DonationExpiryJob.class);

    private final DonationService donations;

    DonationExpiryJob(DonationService donations) {
        this.donations = donations;
    }

    @Scheduled(fixedDelayString = "${donation.expiry.interval:1m}", initialDelayString = "${donation.expiry.interval:1m}")
    void expire() {
        int expired = donations.expireStalePending();
        if (expired > 0) log.info("Expired {} 후원 orders PENDING for over {}", expired, DonationService.EXPIRE_AFTER);
    }
}
