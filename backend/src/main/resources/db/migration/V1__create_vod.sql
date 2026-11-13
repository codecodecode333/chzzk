CREATE TABLE vod (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title             VARCHAR(100) NOT NULL,
    description       TEXT,
    storage_type      VARCHAR(20)  NOT NULL,
    storage_key       VARCHAR(255) NOT NULL UNIQUE,
    original_filename VARCHAR(255) NOT NULL,
    content_type      VARCHAR(255) NOT NULL,
    size_bytes        BIGINT       NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_vod_created_at ON vod (created_at DESC);
