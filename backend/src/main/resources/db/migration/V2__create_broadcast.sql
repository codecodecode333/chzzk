CREATE TABLE broadcast (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title              VARCHAR(100) NOT NULL,
    status             VARCHAR(10)  NOT NULL CHECK (status IN ('LIVE', 'ENDED')),
    publish_key_hash   VARCHAR(64)  NOT NULL,
    started_at         TIMESTAMPTZ  NOT NULL,
    ended_at           TIMESTAMPTZ,
    last_publishing_at TIMESTAMPTZ,
    CONSTRAINT broadcast_ended_at_iff_ended CHECK ((status = 'ENDED') = (ended_at IS NOT NULL))
);

CREATE INDEX idx_broadcast_status_started_at ON broadcast (status, started_at DESC);
CREATE INDEX idx_broadcast_status_ended_at ON broadcast (status, ended_at DESC);
