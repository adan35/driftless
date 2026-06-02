-- Spec 01 — Immutability invariant, enforced at the database.
--
-- The ledger is append-only: corrections are NEW compensating posts, never edits or deletes of
-- existing rows. The application contains no UPDATE/DELETE path against journal_entry, but we make
-- the invariant defence-in-depth so that even a direct SQL statement (or a future bug) is rejected.
--
-- Choice of mechanism: a BEFORE UPDATE OR DELETE trigger that RAISEs an exception. This is preferred
-- over REVOKE UPDATE/DELETE privileges because it is portable and deterministic under Testcontainers
-- (the test runs as the database owner/superuser, for whom table-level GRANTs are not enforced), and
-- it produces a clear, assertable error message. An integration test issues a direct UPDATE and a
-- direct DELETE and asserts both are rejected.

CREATE OR REPLACE FUNCTION reject_journal_entry_mutation()
    RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'journal_entry is append-only: % is not permitted (immutability invariant)', TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_journal_entry_append_only
    BEFORE UPDATE OR DELETE ON journal_entry
    FOR EACH ROW
EXECUTE FUNCTION reject_journal_entry_mutation();
