-- Consumer-side deduplication.
--
-- The outbox in payment-service guarantees at-least-once delivery, which means this service will
-- sometimes see the same event twice: a redelivery after a crash, a rebalance, or a retry. The
-- primary key on event_id is what makes a second delivery a no-op instead of a second effect.
--
-- This is infrastructure bookkeeping, not a ledger concept -- it records what has been *consumed*,
-- never what the money did.

CREATE TABLE processed_events
(
    -- Assigned by the producer (the outbox row id) and carried in the payload and the event-id
    -- header. Being the primary key is the whole mechanism: INSERT ... ON CONFLICT DO NOTHING
    -- turns a duplicate into zero rows affected, atomically, with no read-then-write race.
    event_id         UUID PRIMARY KEY,
    event_type       VARCHAR(64)  NOT NULL,
    payment_id       UUID         NOT NULL,

    -- Where it was read from, kept for debugging and to prove in tests that a duplicate did not
    -- overwrite the first delivery. "offset" and "partition" are awkward as column names in SQL.
    topic            VARCHAR(255) NOT NULL,
    partition_number INT          NOT NULL,
    kafka_offset     BIGINT       NOT NULL,

    payload          TEXT         NOT NULL,
    processed_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX processed_events_by_payment ON processed_events (payment_id);
