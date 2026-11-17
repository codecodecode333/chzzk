-- Which statuses are still open (the payment may yet be approved: PENDING, EXPIRED) is defined here once for all SQL,
-- the donations module's queries and the broadcast module's delete guard alike. DonationStatus.isOpen() is the same
-- rule on the Java side; change both together.
ALTER TABLE donation ADD COLUMN is_open BOOLEAN GENERATED ALWAYS AS (status IN ('PENDING', 'EXPIRED')) STORED;

-- 후원 금액 is a long everywhere in the code; the column matches.
ALTER TABLE donation ALTER COLUMN amount TYPE BIGINT;
