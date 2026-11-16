-- 후원 (ADR-0004): an order created before payment, confirmed only after the payment provider approved it.
-- The amount and the recipient (the 방송's 소유자 at order time) are fixed by the order. Only CONFIRMED rows are 후원.
CREATE TABLE donation (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id     UUID         NOT NULL,
    -- RESTRICT: payment records must not disappear with their 방송.
    broadcast_id BIGINT       NOT NULL REFERENCES broadcast (id) ON DELETE RESTRICT,
    donor_id     BIGINT       NOT NULL REFERENCES member (id),
    recipient_id BIGINT       NOT NULL REFERENCES member (id),
    amount       INT          NOT NULL,
    message      VARCHAR(100),
    status       VARCHAR(10)  NOT NULL,
    payment_key  VARCHAR(200),
    failure_code VARCHAR(100),
    created_at   TIMESTAMPTZ  NOT NULL,
    confirmed_at TIMESTAMPTZ,
    CONSTRAINT uq_donation_order_id UNIQUE (order_id),
    CONSTRAINT uq_donation_payment_key UNIQUE (payment_key),
    CONSTRAINT donation_amount_rule CHECK (amount BETWEEN 1000 AND 1000000 AND amount % 1000 = 0),
    CONSTRAINT donation_status CHECK (status IN ('PENDING', 'CONFIRMED', 'FAILED', 'EXPIRED')),
    CONSTRAINT donation_not_to_self CHECK (donor_id <> recipient_id),
    CONSTRAINT donation_confirmed_iff_paid CHECK (
        (status = 'CONFIRMED') = (confirmed_at IS NOT NULL AND payment_key IS NOT NULL))
);

-- 내역 and 합계 of one 방송 (CONFIRMED only, newest first), and the expiry sweep of old PENDING orders.
CREATE INDEX idx_donation_broadcast_status_id ON donation (broadcast_id, status, id DESC);
CREATE INDEX idx_donation_status_created_at ON donation (status, created_at);
-- Looking up a 회원's own orders and the FKs to member.
CREATE INDEX idx_donation_donor ON donation (donor_id);
CREATE INDEX idx_donation_recipient ON donation (recipient_id);
