-- Spec 06 — Tokenization Lifecycle.
--
-- This module owns its own Flyway history (it starts at V1) on a dedicated migration location
-- (db/migration_tokens, a sibling of the ledger's db/migration and the idempotency module's
-- db/migration_idempotency so none recursively scans another) with its own schema-history table.
-- Two tables:
--
--   * token — the lifecycle aggregate: a card REFERENCE (never the raw PAN) mapped to a token with
--     its own status. Only the status/updated_at columns are mutable; each legal transition advances
--     the status and re-stamps updated_at from the injected Clock.
--
--   * token_status_history — an APPEND-ONLY audit trail, one row per successful transition (including
--     the initial CREATE, whose from_status is NULL). Rows are only ever inserted; the no-UPDATE/
--     no-DELETE rule is enforced defence-in-depth by a trigger, mirroring the ledger's journal_entry.

-- The lifecycle aggregate. card_ref is a log-safe reference/hash of the underlying card; the raw PAN
-- never reaches a column (real PCI/HSM handling is future work, out of scope for Spec 06).
CREATE TABLE token (
    id         UUID         NOT NULL,
    card_ref   VARCHAR(255) NOT NULL,
    status     VARCHAR(16)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_token PRIMARY KEY (id),
    CONSTRAINT ck_token_status CHECK (status IN ('INACTIVE', 'ACTIVE', 'SUSPENDED', 'DEACTIVATED'))
);

-- The append-only audit trail of transitions. from_status is NULL for the CREATE entry.
CREATE TABLE token_status_history (
    id          UUID        NOT NULL,
    token_id    UUID        NOT NULL,
    from_status VARCHAR(16),
    to_status   VARCHAR(16) NOT NULL,
    transition  VARCHAR(16) NOT NULL,
    changed_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_token_status_history PRIMARY KEY (id),
    CONSTRAINT fk_token_status_history_token FOREIGN KEY (token_id) REFERENCES token (id),
    CONSTRAINT ck_token_status_history_from CHECK (
        from_status IS NULL OR from_status IN ('INACTIVE', 'ACTIVE', 'SUSPENDED', 'DEACTIVATED')),
    CONSTRAINT ck_token_status_history_to CHECK (
        to_status IN ('INACTIVE', 'ACTIVE', 'SUSPENDED', 'DEACTIVATED')),
    CONSTRAINT ck_token_status_history_transition CHECK (
        transition IN ('CREATE', 'ACTIVATE', 'SUSPEND', 'RESUME', 'DEACTIVATE'))
);

-- Hot path: read a token's transitions in time order; also backs the per-token transition count the
-- keyless idempotency key derivation uses.
CREATE INDEX ix_token_status_history_token ON token_status_history (token_id, changed_at);

-- Immutability of the audit trail, enforced at the database (defence-in-depth, mirroring the ledger's
-- journal_entry trigger). The application contains no UPDATE/DELETE path against this table; this
-- rejects even a direct SQL statement or a future bug.
CREATE OR REPLACE FUNCTION reject_token_status_history_mutation()
    RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'token_status_history is append-only: % is not permitted (audit-trail invariant)', TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_token_status_history_append_only
    BEFORE UPDATE OR DELETE ON token_status_history
    FOR EACH ROW
EXECUTE FUNCTION reject_token_status_history_mutation();
