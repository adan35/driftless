-- Spec 01 — Ledger Core: immutable double-entry journal.
--
-- Design notes (Spec 01 / CLAUDE.md):
--   * Balance is DERIVED, never stored: there is deliberately no balance column on `account`.
--     Posted balance is always SELECT SUM(...) over `journal_entry`, signed by direction.
--   * `journal_entry` is APPEND-ONLY. The no-UPDATE/DELETE rule is enforced in code (no such path
--     exists) and, defensively, at the database in V2 via a BEFORE UPDATE OR DELETE trigger.
--   * Money is integer minor units: amount_minor is BIGINT, paired with an explicit ISO-4217
--     currency. No floating-point column anywhere on a money value.

-- An immutable account definition. Created once; never mutated.
CREATE TABLE account (
    id         UUID         NOT NULL,
    type       VARCHAR(16)  NOT NULL,
    currency   VARCHAR(3)   NOT NULL,
    name       VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_account PRIMARY KEY (id)
);

-- A posted transaction (journal batch). One row per balanced post().
-- idempotency_key is UNIQUE so a replay collides at the database, not just in application memory.
CREATE TABLE transaction (
    id              UUID         NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    occurred_at     TIMESTAMPTZ  NOT NULL,
    posted_at       TIMESTAMPTZ  NOT NULL,
    description     VARCHAR(512),
    CONSTRAINT pk_transaction PRIMARY KEY (id)
);

-- Unique index on the idempotency key: the database is the final arbiter of replay.
CREATE UNIQUE INDEX ux_transaction_idempotency_key ON transaction (idempotency_key);

-- One immutable, append-only leg of a transaction. sequence_no orders the legs within their tx.
CREATE TABLE journal_entry (
    id             UUID        NOT NULL,
    transaction_id UUID        NOT NULL,
    account_id     UUID        NOT NULL,
    direction      VARCHAR(6)  NOT NULL,
    amount_minor   BIGINT      NOT NULL,
    currency       VARCHAR(3)  NOT NULL,
    sequence_no    INTEGER     NOT NULL,
    CONSTRAINT pk_journal_entry PRIMARY KEY (id),
    CONSTRAINT fk_journal_entry_transaction FOREIGN KEY (transaction_id) REFERENCES transaction (id),
    CONSTRAINT fk_journal_entry_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT ck_journal_entry_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    -- Direction carries the sign; a stored leg amount is strictly positive minor units.
    CONSTRAINT ck_journal_entry_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT uq_journal_entry_tx_sequence UNIQUE (transaction_id, sequence_no)
);

-- Summation by account is the hot read path for balanceOf / reconciliation.
CREATE INDEX ix_journal_entry_account ON journal_entry (account_id);
