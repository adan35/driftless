package io.driftless.ledger.api;

import io.driftless.common.id.AccountId;
import io.driftless.common.id.TxId;
import java.util.List;

/**
 * The immutable double-entry ledger — the foundation every other module moves money through.
 *
 * <p>This interface is the <strong>frozen</strong> cross-module contract (see {@code
 * docs/contracts/ledger-interface.md}). Spec 01 owns the implementation; downstream modules code
 * against this type only. Do not change its shape without reopening the Spec 00/01 freeze gate.
 */
public interface Ledger {

    /** Create an account. Idempotent on a caller-supplied id. */
    Account openAccount(Account account);

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
}
