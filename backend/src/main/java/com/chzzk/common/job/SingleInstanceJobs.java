package com.chzzk.common.job;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a scheduled job's round on only one backend instance at a time (ADR-0005). Each round first tries a PostgreSQL
 * transaction-scoped advisory lock keyed by the job's name; the instance that gets it runs the body inside that
 * transaction, the others skip the round. The lock goes with the transaction, so a crashed run can't leave it held.
 */
@Component
public class SingleInstanceJobs {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    SingleInstanceJobs(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
    }

    /**
     * Runs {@code body} unless another run of {@code job} holds its lock right now.
     *
     * @return whether this run won the lock and ran the body
     */
    public boolean runIfWon(String job, Runnable body) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            Boolean won = jdbc.queryForObject("select pg_try_advisory_xact_lock(hashtextextended(?, 0))",
                    Boolean.class, job);
            if (!Boolean.TRUE.equals(won)) return false;
            body.run();
            return true;
        }));
    }
}
