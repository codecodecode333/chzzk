-- The 후원 내역 is ordered and paged by when each 후원 was confirmed, not by when its order was created: an order made
-- earlier but paid later must still reach a viewer polling with ?after= (spec story 24). Every confirmation takes the
-- next value of this sequence in the same conditional update that confirms the order; confirmations of one 방송 are
-- serialized (DonationService), so they commit in sequence order and a poller's cursor never skips one.
CREATE SEQUENCE donation_confirmed_seq;

ALTER TABLE donation ADD COLUMN confirmed_seq BIGINT;

-- Existing 후원, in the order they were confirmed.
UPDATE donation d
SET confirmed_seq = numbered.seq
FROM (SELECT id, nextval('donation_confirmed_seq') AS seq
      FROM (SELECT id FROM donation WHERE status = 'CONFIRMED' ORDER BY confirmed_at, id) confirmed) numbered
WHERE d.id = numbered.id;

ALTER TABLE donation ADD CONSTRAINT uq_donation_confirmed_seq UNIQUE (confirmed_seq);
ALTER TABLE donation DROP CONSTRAINT donation_confirmed_iff_paid;
ALTER TABLE donation ADD CONSTRAINT donation_confirmed_iff_paid CHECK (
    (status = 'CONFIRMED') = (confirmed_at IS NOT NULL AND payment_key IS NOT NULL AND confirmed_seq IS NOT NULL));

-- 내역 of one 방송, newest confirmed first.
CREATE INDEX idx_donation_broadcast_confirmed_seq ON donation (broadcast_id, confirmed_seq DESC)
    WHERE status = 'CONFIRMED';
