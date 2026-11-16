-- VOD ownership: every VOD has a 소유자 (회원). Existing rows are pre-auth dev data with nobody to own them, so they
-- are deleted (their 영상 파일 stay on disk; see the README for cleaning them up).
DELETE FROM vod;

ALTER TABLE vod ADD COLUMN owner_id BIGINT NOT NULL REFERENCES member (id);

CREATE INDEX idx_vod_owner_id ON vod (owner_id);
