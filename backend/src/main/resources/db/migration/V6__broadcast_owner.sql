-- 방송 소유자 (ticket 03). Existing 방송 predate 회원 and have no owner: they are pre-auth dev data, so they go.
DELETE FROM broadcast;

ALTER TABLE broadcast ADD COLUMN owner_id BIGINT NOT NULL REFERENCES member (id);

-- A 회원 has at most one 방송 중 방송: the race-safe backstop for "방송 시작" answering 409.
CREATE UNIQUE INDEX uq_broadcast_live_owner ON broadcast (owner_id) WHERE status = 'LIVE';
CREATE INDEX idx_broadcast_owner ON broadcast (owner_id);
