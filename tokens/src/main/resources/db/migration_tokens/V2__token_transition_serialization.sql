-- Spec 06 review fix (C1 / W1): per-token transition serialization + last-transition anchor.
--
-- C1 — Concurrent conflicting transitions could resurrect a terminal token because two different
-- transitions on the same token took different idempotency keys and the row was read without a lock.
-- The service now loads the token FOR UPDATE inside the guarded supplier so transitions on the same
-- token serialize. The `version` column adds optimistic-locking defence-in-depth on top of that
-- pessimistic lock.
--
-- W1 — The keyless surface must only treat a transition as an idempotent replay when the SAME
-- transition was the one that last advanced the token. `last_transition` records that most-recent
-- advancing transition on the aggregate itself, giving a deterministic anchor that does not depend on
-- history-row ordering (which can tie under a fixed Clock).

ALTER TABLE token ADD COLUMN last_transition VARCHAR(16);

-- Backfill any pre-existing token from its most recent history row (no-op on a fresh schema).
UPDATE token t
SET last_transition = (
    SELECT h.transition
    FROM token_status_history h
    WHERE h.token_id = t.id
    ORDER BY h.changed_at DESC
    LIMIT 1);

ALTER TABLE token ALTER COLUMN last_transition SET NOT NULL;

ALTER TABLE token ADD CONSTRAINT ck_token_last_transition
    CHECK (last_transition IN ('CREATE', 'ACTIVATE', 'SUSPEND', 'RESUME', 'DEACTIVATE'));

ALTER TABLE token ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
