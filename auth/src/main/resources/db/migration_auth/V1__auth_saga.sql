-- Spec 03 — Auth Lifecycle Saga.
--
-- This module owns its own Flyway history (it starts at V1) on a dedicated migration location
-- (db/migration_auth, a sibling of the ledger's db/migration, the idempotency module's
-- db/migration_idempotency and the tokens module's db/migration_tokens, so none recursively scans
-- another) with its own schema-history table. Two tables:
--
--   * authorization — the saga aggregate: one row per authorize request, advanced through the
--     DECLINED / AUTHORIZING / AUTHORIZED / CAPTURED / REVERSED / COMPENSATING state machine. Only
--     the status/partner_ref/decline_reason/updated_at columns are mutable; the version column gives
--     optimistic-lock defence-in-depth on top of the SELECT ... FOR UPDATE the saga loads under.
--
--   * hold — the available-vs-posted seam: one ACTIVE row per placed hold reduces the account's
--     available balance (it FEEDS the ledger HoldView via SUM over ACTIVE rows) while posted is
--     unchanged; releasing a hold flips it to RELEASED. Holds are never deleted (immutability):
--     available is restored by the status change, never by a DELETE.

CREATE TABLE "authorization" (
    id              UUID         NOT NULL,
    account_id      UUID         NOT NULL,
    amount_minor    BIGINT       NOT NULL,
    currency        VARCHAR(3)   NOT NULL,
    mcc             VARCHAR(4),
    merchant_id     VARCHAR(64),
    token_id        UUID,
    card_ref        VARCHAR(255) NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    partner_ref     VARCHAR(64),
    decline_reason  VARCHAR(64),
    idempotency_key VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    version         BIGINT       NOT NULL,
    CONSTRAINT pk_authorization PRIMARY KEY (id),
    CONSTRAINT ck_authorization_status CHECK (
        status IN ('DECLINED', 'AUTHORIZING', 'AUTHORIZED', 'CAPTURED', 'REVERSED', 'COMPENSATING')),
    CONSTRAINT ck_authorization_amount_positive CHECK (amount_minor > 0)
);

-- The recovery sweep scans for in-flight rows (AUTHORIZING / COMPENSATING) older than a threshold;
-- this partial index keeps that scan cheap as terminal rows accumulate.
CREATE INDEX ix_authorization_inflight ON "authorization" (status, updated_at)
    WHERE status IN ('AUTHORIZING', 'COMPENSATING');

CREATE TABLE hold (
    id                UUID        NOT NULL,
    authorization_id  UUID        NOT NULL,
    account_id        UUID        NOT NULL,
    amount_minor      BIGINT      NOT NULL,
    currency          VARCHAR(3)  NOT NULL,
    status            VARCHAR(8)  NOT NULL,
    placed_at         TIMESTAMPTZ NOT NULL,
    released_at       TIMESTAMPTZ,
    CONSTRAINT pk_hold PRIMARY KEY (id),
    CONSTRAINT fk_hold_authorization FOREIGN KEY (authorization_id) REFERENCES "authorization" (id),
    CONSTRAINT ck_hold_status CHECK (status IN ('ACTIVE', 'RELEASED')),
    CONSTRAINT ck_hold_amount_positive CHECK (amount_minor > 0)
);

-- The HoldView sum: SUM(amount_minor) over ACTIVE holds for an (account_id, currency). This index
-- backs that aggregation so available = posted - activeHoldTotal stays cheap on the hot path.
CREATE INDEX ix_hold_active_account_currency ON hold (account_id, currency, status);
