-- Spec 02 — Idempotency keys + transactional outbox.
--
-- This module owns its own Flyway history (it starts at V1) on a dedicated migration location
-- (db/migration_idempotency, a sibling of the ledger's db/migration so neither recursively scans the
-- other) with its own schema-history table. Two tables:
--
--   * idempotency_record — the replay guard's fingerprint store. Inserted in the SAME transaction as
--     the business write, so the side effect and its idempotency record commit/roll back together.
--     A second request with the same (scope, key) collides on the unique constraint; the loser reads
--     the stored result instead of re-running. Append-only except the status/completion columns,
--     which move IN_PROGRESS -> COMPLETED exactly once.
--
--   * outbox_event — the transactional outbox. Appended in the caller's transaction (atomic with the
--     state change). A scheduled relay reads PENDING rows in id order with FOR UPDATE SKIP LOCKED,
--     publishes them, and marks PUBLISHED. Append-only except the status/published_at/attempts
--     columns. Ordering is by the monotonic BIGINT id (insertion order), preserved per aggregate.

-- The replay guard's fingerprint store. response_blob holds the canonical serialized result; a
-- replay reconstructs the typed value from response_blob + response_type without re-running the op.
CREATE TABLE idempotency_record (
    scope         VARCHAR(128)  NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash  VARCHAR(128)  NOT NULL,
    status        VARCHAR(16)   NOT NULL,
    response_blob TEXT,
    response_type VARCHAR(512),
    created_at    TIMESTAMPTZ   NOT NULL,
    completed_at  TIMESTAMPTZ,
    CONSTRAINT pk_idempotency_record PRIMARY KEY (scope, idempotency_key),
    CONSTRAINT ck_idempotency_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED'))
);

-- The transactional outbox. id is a monotonic sequence so the relay can both order delivery and page
-- with FOR UPDATE SKIP LOCKED deterministically.
CREATE TABLE outbox_event (
    id             BIGINT       GENERATED ALWAYS AS IDENTITY,
    aggregate_type VARCHAR(128) NOT NULL,
    aggregate_id   VARCHAR(255) NOT NULL,
    event_type     VARCHAR(128) NOT NULL,
    payload_json   TEXT         NOT NULL,
    occurred_at    TIMESTAMPTZ  NOT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    published_at   TIMESTAMPTZ,
    attempts       INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT pk_outbox_event PRIMARY KEY (id),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED'))
);

-- Hot path for the relay: scan the oldest PENDING rows in id order. A partial index keeps the
-- working set small as PUBLISHED rows accumulate.
CREATE INDEX ix_outbox_event_pending ON outbox_event (id) WHERE status = 'PENDING';
