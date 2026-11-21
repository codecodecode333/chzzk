package com.chzzk.donation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The expiry sweep runs on its own schedule, switched by {@code donation.expiry.enabled} (on by default), whatever the
 * broadcast module's scheduling is set to.
 */
class DonationExpiryScheduleTest {

    /** A job that only counts its runs. */
    static class CountingJob extends DonationExpiryJob {
        final CountDownLatch runs = new CountDownLatch(2);

        CountingJob() {
            super(null, null);
        }

        @Override
        void expire() {
            runs.countDown();
        }
    }

    final CountingJob job = new CountingJob();

    final ApplicationContextRunner context = new ApplicationContextRunner()
            // like SpringApplication does, so "10ms" converts to a Duration
            .withInitializer(app -> app.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withBean(DonationExpiryJob.class, () -> job)
            .withUserConfiguration(DonationExpirySchedule.class)
            .withPropertyValues("donation.expiry.interval=10ms");

    @Test
    void runsByDefaultEvenWithTheBroadcastSchedulingOff() {
        context.withPropertyValues("broadcast.publishing-monitor.enabled=false").run(app -> {
            assertThat(app).hasSingleBean(DonationExpirySchedule.class);
            assertThat(job.runs.await(5, TimeUnit.SECONDS)).as("the sweep ran repeatedly").isTrue();
        });
    }

    @Test
    void canBeSwitchedOffOnItsOwn() {
        context.withPropertyValues("donation.expiry.enabled=false").run(app ->
                assertThat(app).doesNotHaveBean(DonationExpirySchedule.class));
    }
}
