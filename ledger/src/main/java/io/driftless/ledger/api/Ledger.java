package io.driftless.ledger.api;

import io.driftless.common.id.AccountId;
import io.driftless.common.id.TxId;
import java.util.List;
import java.util.Optional;

/**
 * The immutable double-entry ledger — the foundation every other module moves money through.
 *
 * <p>This interface is the <strong>frozen</strong> cross-module contract (see {@code
 * docs/contracts/ledger-interface.md}). Spec 01 owns the implementation; downstream modules code
 * against this type only. Do not change the shape of an existing method without reopening the Spec
 * 00/01 freeze gate.
 *
 * <p><strong>Additive extension (API-refinement pass).</strong> {@link #findAccount(AccountId)} and
 * {@link #entriesAfter(AccountId, EntryCursor, int)} were <em>added</em> to support an account-detail
 * REST endpoint and stable keyset (seek) statement pagination. They are purely additive — no existing
 * method signature, record component, or behaviour changed — so the freeze on the established shape
 * holds; only the surface grew.
 */
public interface Ledger {

    /** Create an account. Idempotent on a caller-supplied id. */
    Account openAccount(Account account);

    /** Look up an account by id; empty when no such account exists. (Additive — see type javadoc.) */
    Optional<Account> findAccount(AccountId account);

    /**
     * Atomically post one balanced transaction.
     *
     * <ul>
     *   <li>Rejects unbalanced requests ({@link BalanceInvariantViolation}).
     *   <li>Rejects currency mismatch between a line's {@code Money} and its account.
     *   <li>Append-only: never updates/deletes existing entries.
     *   <li>Idempotent: the same {@code idempotencyKey} returns the original {@link PostingResult}
     *       with {@code replayed=true}.
     * </ul>
     */
    PostingResult post(PostingRequest request);

    /** Posted + available balance for an account, computed by summation over entries (+ holds). */
    Balance balanceOf(AccountId account);

    /** Read a posted transaction and its entries (immutable view). */
    Transaction findTransaction(TxId id);

    /** Stream/page entries for an account (for reconciliation and statements). */
    List<JournalEntry> entriesFor(AccountId account, Page page);

    /**
     * Keyset (seek) page of an account's entries, ordered oldest-first by the global, immutable {@code
     * entry_seq}. {@code cursor} is {@link EntryCursor#START} (or {@code null}) for the first page and
     * otherwise the {@link EntryPage#nextCursor()} returned by the previous page; {@code limit} is
     * capped to a server-side maximum. Unlike {@link #entriesFor(AccountId, Page)} this never uses
     * {@code OFFSET}, so it does not scan skipped rows.
     *
     * <p><strong>Concurrency guarantee (precise).</strong> Paging is stable and gap-free for entries
     * that are committed in {@code entry_seq} order: a row visible to the reader always lands on a
     * page at or after its sequence, and no row is ever duplicated. Because Postgres sequences are
     * non-transactional, a row can be <em>assigned</em> a lower {@code entry_seq} yet <em>commit</em>
     * after a higher-sequence row; a continuing page whose cursor has already advanced past the
     * higher sequence may therefore omit that still-in-flight lower row until a subsequent read. The
     * durable ledger never loses or duplicates an entry — a fresh read from {@link EntryCursor#START}
     * always returns the complete set once the writer commits. (Additive — see type javadoc.)
     */
    EntryPage entriesAfter(AccountId account, EntryCursor cursor, int limit);
}
