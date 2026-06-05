-- Spec 07 — Reconciliation & Reliability.
--
-- This module owns its own Flyway history (it starts at V1) on a dedicated migration location
-- (db/migration_recon, a sibling of the ledger's db/migration and the idempotency / tokens / auth
-- locations, so none recursively scans another) with its own schema-history table.
--
-- One table: reconciliation_result — the persisted, structured output of each balance-proof run. It
-- is the evidence the README links to and the read model Spec 08's dashboard renders. It is APPEND-
-- ONLY by usage (a new run is a new row; rows are never mutated). The per-check outcomes and the
-- implicated accounts/transactions are stored as canonical JSON so the structured RCA detail
-- survives without a sprawling relational schema. Reconciliation is a CONSUMER of the other modules'
-- committed state: this migration creates no foreign keys to ledger/auth/outbox tables — those are
-- summed read-only, never owned here.

CREATE TABLE reconciliation_result (
    id                UUID        NOT NULL,
    ran_at            TIMESTAMPTZ NOT NULL,
    passed            BOOLEAN     NOT NULL,
    total_drift_minor BIGINT      NOT NULL,
    checks_json       TEXT        NOT NULL,
    offenders_json    TEXT        NOT NULL,
    summary           VARCHAR(512) NOT NULL,
    CONSTRAINT pk_reconciliation_result PRIMARY KEY (id)
);

-- The dashboard's hot read is "the latest run" and "recent failed runs"; this index backs both an
-- ordered-by-time scan and the partial filter on drift.
CREATE INDEX ix_reconciliation_result_ran_at ON reconciliation_result (ran_at DESC);
CREATE INDEX ix_reconciliation_result_failed ON reconciliation_result (ran_at DESC) WHERE passed = FALSE;
