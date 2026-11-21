package com.chzzk.common.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Moves a job's rounds along for tests that run its rounds by hand: the job's lease is taken on the database clock,
 * which a test's injected clock doesn't move, so "the interval has passed" is done here instead of by waiting.
 */
public final class JobRounds {

    private JobRounds() {}

    /** The job's interval passes: whoever started its last round, the next round on any instance may run. */
    public static void intervalPasses(JdbcTemplate jdbc, String job) {
        jdbc.update("update job_lease set last_started_at = last_started_at - interval '1 day' where job = ?", job);
    }

    /** Another backend instance starts a round of the job now, after its interval has passed. */
    public static void anotherInstanceStartsARound(JobLeases jobs, JdbcTemplate jdbc, String job, Duration interval) {
        intervalPasses(jdbc, job);
        assertThat(jobs.runIfClaimed(job, interval, () -> {})).as("the other instance claimed the round").isTrue();
    }
}
