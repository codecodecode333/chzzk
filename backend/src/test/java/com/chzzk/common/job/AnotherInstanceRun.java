package com.chzzk.common.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A run of a job, as another backend instance would have it, that is in the middle of its round: it has won the job's
 * lock (on its own connection) and keeps it until {@link #close()}.
 */
public final class AnotherInstanceRun implements AutoCloseable {

    private final CountDownLatch release = new CountDownLatch(1);
    private final CompletableFuture<Boolean> run;

    private AnotherInstanceRun(SingleInstanceJobs jobs, String job) throws InterruptedException {
        CountDownLatch holding = new CountDownLatch(1);
        run = CompletableFuture.supplyAsync(() -> jobs.runIfWon(job, () -> {
            holding.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(holding.await(10, TimeUnit.SECONDS)).as("the other instance's run got the lock").isTrue();
    }

    /** Starts a run of {@code job} on another thread; returns once it holds the job's lock. */
    public static AnotherInstanceRun midRound(SingleInstanceJobs jobs, String job) throws InterruptedException {
        return new AnotherInstanceRun(jobs, job);
    }

    /** Lets the run finish its round. */
    @Override
    public void close() throws Exception {
        release.countDown();
        assertThat(run.get(10, TimeUnit.SECONDS)).isTrue();
    }
}
