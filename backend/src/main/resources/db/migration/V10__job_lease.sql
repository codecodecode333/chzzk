-- One row per scheduled job: when a backend instance last started a round of it (database clock). An instance runs a
-- round only if it moves last_started_at forward from a value older than most of the job's interval, so across all
-- instances each job runs about once per interval (ADR-0005, common.job.JobLeases).
CREATE TABLE job_lease (
    job             VARCHAR(100) PRIMARY KEY,
    last_started_at TIMESTAMPTZ  NOT NULL
);
