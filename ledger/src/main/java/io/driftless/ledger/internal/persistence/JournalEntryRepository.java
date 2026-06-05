package io.driftless.ledger.internal.persistence;

import io.driftless.ledger.api.Direction;
import io.driftless.ledger.spi.UnbalancedTransaction;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access to the append-only {@code journal_entry} table.
 *
 * <p>This repository is read/insert only — it deliberately exposes no {@code update}/{@code delete}
 * derived methods, honoring the immutability invariant at the application layer (the database
 * trigger from migration {@code V2} backs it up). Balance is computed here by summation, never
 * stored.
 */
public interface JournalEntryRepository extends JpaRepository<JournalEntryEntity, UUID> {

    /**
     * Net posted balance for one account and currency, in minor units: the sum of debit legs minus
     * the sum of credit legs. Returns {@code 0} when the account has no entries. The sign convention
     * (debits positive) lives in the ledger, mirroring {@link Direction}.
     */
    @Query(
            """
            SELECT COALESCE(SUM(CASE WHEN e.direction = io.driftless.ledger.api.Direction.DEBIT
                                     THEN e.amountMinor ELSE -e.amountMinor END), 0)
            FROM JournalEntryEntity e
            WHERE e.accountId = :accountId AND e.currency = :currency
            """)
    long netPostedMinor(@Param("accountId") UUID accountId, @Param("currency") String currency);

    /** Distinct currencies that have at least one entry for the account (usually one in the MVP). */
    @Query("SELECT DISTINCT e.currency FROM JournalEntryEntity e WHERE e.accountId = :accountId")
    List<String> currenciesFor(@Param("accountId") UUID accountId);

    /** A page of an account's entries, oldest first, for reconciliation and statements. */
    @Query("SELECT e FROM JournalEntryEntity e WHERE e.accountId = :accountId ORDER BY e.sequenceNo ASC, e.id ASC")
    List<JournalEntryEntity> findPageForAccount(@Param("accountId") UUID accountId, Pageable pageable);

    /**
     * Keyset (seek) page of an account's entries: every entry whose global {@code entry_seq} is
     * strictly greater than {@code afterSeq}, oldest first, limited by {@code pageable}. This is the
     * seek predicate {@code entry_seq > ? ORDER BY entry_seq ASC LIMIT ?} — no {@code OFFSET}, so it
     * never scans skipped rows. Paging is gap-free and duplicate-free for entries committed in {@code
     * entry_seq} order; since Postgres sequences are non-transactional, a lower-seq row committing
     * after a higher-seq row already paged past can be transiently omitted until a fresh read (never
     * lost or duplicated). {@code afterSeq = 0} starts from the beginning (entry sequences are {@code
     * >= 1}).
     */
    @Query(
            """
            SELECT e FROM JournalEntryEntity e
            WHERE e.accountId = :accountId AND e.entrySeq > :afterSeq
            ORDER BY e.entrySeq ASC
            """)
    List<JournalEntryEntity> findEntriesAfter(
            @Param("accountId") UUID accountId, @Param("afterSeq") long afterSeq, Pageable pageable);

    /**
     * Signed global sum of every journal entry in a currency, in minor units (debits positive,
     * credits negative). For a correct ledger this is always {@code 0}. Seed of the Spec 07
     * zero-drift property; {@link Optional#empty()} when no entries exist for the currency.
     */
    @Query(
            """
            SELECT COALESCE(SUM(CASE WHEN e.direction = io.driftless.ledger.api.Direction.DEBIT
                                     THEN e.amountMinor ELSE -e.amountMinor END), 0)
            FROM JournalEntryEntity e
            WHERE e.currency = :currency
            """)
    long globalSignedSumMinor(@Param("currency") String currency);

    /** All distinct currencies that appear anywhere in the journal. */
    @Query("SELECT DISTINCT e.currency FROM JournalEntryEntity e")
    List<String> allCurrencies();

    /**
     * Every posted transaction whose legs do not net to zero in a currency, projected to {@link
     * UnbalancedTransaction}. Empty for a correct ledger; the precise per-transaction offender list
     * behind the Spec 07 reconciliation checks. Strictly stronger than the global sum: two unbalanced
     * transactions that coincidentally offset still surface here individually.
     */
    @Query(
            """
            SELECT new io.driftless.ledger.spi.UnbalancedTransaction(
                e.transaction.id,
                e.currency,
                SUM(CASE WHEN e.direction = io.driftless.ledger.api.Direction.DEBIT
                         THEN e.amountMinor ELSE -e.amountMinor END))
            FROM JournalEntryEntity e
            GROUP BY e.transaction.id, e.currency
            HAVING SUM(CASE WHEN e.direction = io.driftless.ledger.api.Direction.DEBIT
                            THEN e.amountMinor ELSE -e.amountMinor END) <> 0
            """)
    List<UnbalancedTransaction> findUnbalancedTransactions();

    /** Distinct accounts touched by the given transactions, to name implicated accounts for RCA. */
    @Query("SELECT DISTINCT e.accountId FROM JournalEntryEntity e WHERE e.transaction.id IN :transactionIds")
    List<UUID> findAccountsForTransactions(@Param("transactionIds") Collection<UUID> transactionIds);
}
