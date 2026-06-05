-- Spec 01 refinement — global monotonic entry sequence for stable keyset (seek) pagination.
--
-- Why: `sequence_no` orders the legs WITHIN a transaction (0,1,...) and is therefore NOT globally
-- monotonic across an account's entry stream; `id` is a random UUID. Neither alone gives a stable,
-- append-safe sort key. `entry_seq` is a single global, strictly-increasing counter assigned at
-- insert time, so it is an immutable, unique key for an index-range seek instead of OFFSET.
--
-- Guarantee (precise): keyset paging over `entry_seq` is gap-free and duplicate-free for entries
-- committed in sequence order. Postgres sequences are NON-TRANSACTIONAL, so a row can be ASSIGNED a
-- lower `entry_seq` yet COMMIT after a higher-sequence row; a page whose cursor has already advanced
-- past the higher sequence may transiently OMIT that still-in-flight lower row until a later read.
-- The durable ledger never loses or duplicates an entry — a fresh read from the start always returns
-- the complete set once writers commit.
--
-- Append-only is preserved: this only ADDS a column (the V2 BEFORE UPDATE/DELETE trigger still
-- rejects any mutation of existing rows). The column is database-generated (DEFAULT nextval); the
-- application never writes it.

CREATE SEQUENCE journal_entry_seq START WITH 1 INCREMENT BY 1;

-- Adding a column with a volatile default rewrites the table and assigns each existing row a distinct,
-- increasing value in physical (insertion) order — a correct monotonic backfill for any prior rows.
-- This relies on the append-only guarantee: the V2 trigger blocks UPDATE/DELETE, so no prior row was
-- ever rewritten in a way that would reorder the heap relative to insertion order.
ALTER TABLE journal_entry
    ADD COLUMN entry_seq BIGINT NOT NULL DEFAULT nextval('journal_entry_seq');

ALTER SEQUENCE journal_entry_seq OWNED BY journal_entry.entry_seq;

-- Unique global order; and a covering (account_id, entry_seq) index so the seek predicate is an
-- index range scan, not a sort.
CREATE UNIQUE INDEX ux_journal_entry_seq ON journal_entry (entry_seq);
CREATE INDEX ix_journal_entry_account_seq ON journal_entry (account_id, entry_seq);
