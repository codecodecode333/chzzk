package com.chzzk.common.job;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Job exclusivity seam: on the shared PostgreSQL, a run of a job does its work only if no other run of the same job
 * (on this instance or another) is doing it right now. Each run borrows its own connection from the pool, as runs on
 * different instances would.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@Import(SingleInstanceJobs.class)
class SingleInstanceJobsTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    SingleInstanceJobs jobs;

    final AtomicInteger bodyRuns = new AtomicInteger();

    @Test
    void aRunOverlappingAnotherRunOfTheSameJobSkipsItsWork() throws Exception {
        boolean ran;
        try (var other = AnotherInstanceRun.midRound(jobs, "송출 감시")) {
            ran = jobs.runIfWon("송출 감시", bodyRuns::incrementAndGet);
        }

        assertThat(ran).isFalse();
        assertThat(bodyRuns).hasValue(0);
    }

    @Test
    void runsOneAfterAnotherEachDoTheirWork() {
        assertThat(jobs.runIfWon("후원 만료", bodyRuns::incrementAndGet)).isTrue();
        assertThat(jobs.runIfWon("후원 만료", bodyRuns::incrementAndGet)).isTrue();

        assertThat(bodyRuns).hasValue(2);
    }

    @Test
    void differentJobsDoNotBlockEachOther() throws Exception {
        boolean ran;
        try (var other = AnotherInstanceRun.midRound(jobs, "송출 감시")) {
            ran = jobs.runIfWon("후원 만료", bodyRuns::incrementAndGet);
        }

        assertThat(ran).isTrue();
        assertThat(bodyRuns).hasValue(1);
    }
}
