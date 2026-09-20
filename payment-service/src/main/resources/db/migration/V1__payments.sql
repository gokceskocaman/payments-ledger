-- A payment is the *request* to move money; the movement itself lives in account-service's ledger.
-- This table is the record of intent plus the outcome, and the idempotency guard in front of both.

CREATE TABLE payments
(
    id              UUID PRIMARY KEY,
    -- Client-supplied, from the Idempotency-Key header. Unique, so the same key can only ever
    -- name one payment no matter how many times it is submitted or how concurrently.
    idempotency_key VARCHAR(255) NOT NULL,
    -- SHA-256 of the canonical request. Lets us tell "this is a retry" from "this key was reused
    -- for a different payment" without storing or re-comparing the raw body.
    request_hash    VARCHAR(64)  NOT NULL,

    from_account_id BIGINT       NOT NULL,
    to_account_id   BIGINT       NOT NULL,
    amount          BIGINT       NOT NULL,
    currency        VARCHAR(3)   NOT NULL,

    status          VARCHAR(16)  NOT NULL,
    failure_reason  VARCHAR(500),

    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT payments_idempotency_key_unique UNIQUE (idempotency_key),
    CONSTRAINT payments_status_known CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    CONSTRAINT payments_amount_positive CHECK (amount > 0),
    CONSTRAINT payments_currency_iso4217 CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT payments_distinct_accounts CHECK (from_account_id <> to_account_id),
    -- A reason only makes sense on a failure, and a failure without one is unexplainable.
    CONSTRAINT payments_failure_reason_matches_status
        CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL))
);

-- PENDING payments are the ones a reconciliation job has to chase, so it must not scan the table.
CREATE INDEX payments_pending_by_age ON payments (created_at) WHERE status = 'PENDING';

-- PENDING -> COMPLETED | FAILED, and nothing else. COMPLETED and FAILED are terminal: once a
-- customer has been told an outcome, no code path may quietly change it.
CREATE FUNCTION payments_forbid_illegal_transition() RETURNS TRIGGER AS
$$
BEGIN
    IF OLD.status <> NEW.status AND OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION 'payment % is already % and cannot become %', OLD.id, OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER payments_status_transitions
    BEFORE UPDATE
    ON payments
    FOR EACH ROW
EXECUTE FUNCTION payments_forbid_illegal_transition();
