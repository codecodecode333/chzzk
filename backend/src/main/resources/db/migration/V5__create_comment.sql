-- 댓글: on exactly one target, a VOD or a 방송, and gone with it. edited is derived from updated_at.
CREATE TABLE comment (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    vod_id       BIGINT REFERENCES vod (id) ON DELETE CASCADE,
    broadcast_id BIGINT REFERENCES broadcast (id) ON DELETE CASCADE,
    author_id    BIGINT       NOT NULL REFERENCES member (id),
    content      VARCHAR(500) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ,
    CONSTRAINT comment_exactly_one_target CHECK ((vod_id IS NULL) <> (broadcast_id IS NULL))
);

CREATE INDEX idx_comment_vod_id ON comment (vod_id, id DESC);
CREATE INDEX idx_comment_broadcast_id ON comment (broadcast_id, id DESC);
