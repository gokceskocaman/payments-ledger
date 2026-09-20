-- Transactional outbox.
--
-- An event is written here in the *same transaction* as the payment state change it describes, so
-- the two can never disagree: either both are committed or neither is. A separate relay moves the
-- rows to Kafka afterwards. See docs/design-notes.md for why publishing from the request thread
-- cannot be made safe.

CREATE TABLE outbox_events
(
    id             UUID PRIMARY KEY,
    -- The Kafka message key. Keying by payment id puts every event for one payment on the same
    -- partition, which is what keeps PaymentCreated ahead of PaymentCompleted for a consumer.
    aggregate_id   UUID         NOT NULL,
    aggregate_type VARCHAR(32)  NOT NULL,
    event_type     VARCHAR(64)  NOT NULL,
    -- Opaque to this service after it is written: the relay forwards the bytes and never looks
    -- inside. Stored as text rather than jsonb because nothing here queries into the payload.
    payload        TEXT         NOT NULL,

    occurred_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- NULL means "not yet on the broker". This column is the entire state machine.
    published_at   TIMESTAMPTZ,
    attempts       INT          NOT NULL DEFAULT 0,
    last_error     VARCHAR(500),

    CONSTRAINT outbox_events_type_known
        CHECK (event_type IN ('PaymentCreated', 'PaymentCompleted', 'PaymentFailed'))
);

-- The relay's only query. Partial, so it stays the size of the backlog rather than the size of
-- history: once an event is published it drops out of the index entirely.
CREATE INDEX outbox_events_unpublished ON outbox_events (occurred_at) WHERE published_at IS NULL;
