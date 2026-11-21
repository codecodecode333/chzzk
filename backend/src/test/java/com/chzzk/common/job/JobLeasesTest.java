package com.chzzk.common.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Job lease seam: on the shared PostgreSQL, a job's round does its work only if no instance (this one or another)
 * has started a round of the same job within its interval, so across all instances the job runs about once per
 * interval. Every instance shares the one database, so two calls here stand for rounds on two instances.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@Import(JobLeases.class)
class JobLeasesTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static final Duration INTERVAL = Duration.ofMillis(800);

    @Autowired
    JobLeases jobs;

    final AtomicInteger bodyRuns = new AtomicInteger();

    @Test
    void ofTwoInstancesStartingTheSameRoundOnlyOneDoesTheWork() {
        boolean first = jobs.runIfClaimed("송출 감시", INTERVAL, bodyRuns::incrementAndGet);
        boolean second = jobs.runIfClaimed("송출 감시", INTERVAL, bodyRuns::incrementAndGet);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(bodyRuns).hasValue(1);
    }

    @Test
    void theNextRoundAfterTheIntervalRunsAgain() throws InterruptedException {
        assertThat(jobs.runIfClaimed("후원 만료", INTERVAL, bodyRuns::incrementAndGet)).isTrue();

        Thread.sleep(INTERVAL.toMillis());

        assertThat(jobs.runIfClaimed("후원 만료", INTERVAL, bodyRuns::incrementAndGet)).isTrue();
        assertThat(bodyRuns).hasValue(2);
    }

    @Test
    void differentJobsDoNotShareASlot() {
        assertThat(jobs.runIfClaimed("감시 A", INTERVAL, bodyRuns::incrementAndGet)).isTrue();
        assertThat(jobs.runIfClaimed("만료 B", INTERVAL, bodyRuns::incrementAndGet)).isTrue();

        assertThat(bodyRuns).hasValue(2);
    }

    @Test
    void aFailedRoundStillHoldsTheSlotUntilTheNextInterval() {
        try {
            jobs.runIfClaimed("실패하는 작업", INTERVAL, () -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // the round's failure reaches the caller
        }

        assertThat(jobs.runIfClaimed("실패하는 작업", INTERVAL, bodyRuns::incrementAndGet)).isFalse();
    }
}
