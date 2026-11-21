package com.chzzk.common.job;

import java.time.Duration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Makes a scheduled job run about once per interval across all backend instances (ADR-0005). Every instance keeps its
 * own schedule; at each round it first tries to claim the job's lease in PostgreSQL: the {@code job_lease} row's
 * {@code last_started_at} is moved to now only if it is older than {@value #CLAIMABLE_PERCENT}% of the interval. The
 * one instance whose claim moves it runs the round; the others skip it. Times are the database's {@code now()}, so
 * instances' clocks don't have to agree. The claim is a short transaction of its own; the round's work runs after it,
 * outside any transaction of this class, with whatever transactions the work opens itself. A round that fails still
 * used its slot: the next one is due an interval later.
 */
@Component
public class JobLeases {

    /** A round may be claimed once this share of the interval has passed since the last, so jitter can't skip one. */
    static final int CLAIMABLE_PERCENT = 90;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate claimTransaction;

    JobLeases(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.claimTransaction = new TransactionTemplate(transactions);
        this.claimTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Runs {@code body} if this round of {@code job} claims its lease, i.e. no instance started a round of it within
     * (most of) {@code interval}.
     *
     * @return whether this round claimed the lease and ran the body
     */
    public boolean runIfClaimed(String job, Duration interval, Runnable body) {
        double claimableAfterSeconds = interval.toMillis() * CLAIMABLE_PERCENT / 100.0 / 1000.0;
        Integer claimed = claimTransaction.execute(tx -> jdbc.update("""
                insert into job_lease (job, last_started_at) values (?, now())
                on conflict (job) do update set last_started_at = excluded.last_started_at
                where job_lease.last_started_at <= now() - make_interval(secs => ?)
                """, job, claimableAfterSeconds));
        if (claimed == null || claimed == 0) return false;
        body.run();
        return true;
    }
}
