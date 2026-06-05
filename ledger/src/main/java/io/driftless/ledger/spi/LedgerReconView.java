package io.driftless.ledger.spi;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Read-only SPI the ledger publishes for <em>reconciliation</em>: the summation views over committed
 * {@code journal_entry} state that a balance-proof job needs but that the frozen {@link
 * io.driftless.ledger.api.Ledger} interface deliberately does not expose (it has no ledger-wide
 * global-sum method).
 *
 * <p>Defining it here lets the Spec 07 reconciliation job inspect committed ledger state through a
 * published contract instead of binding to the ledger's internal JPA repository — honoring the
 * "integrate through {@code api}/{@code spi}, never another module's internals" boundary. It is
 * strictly read-only: reconciliation observes committed history, it never edits the ledger.
 *
 * <p>This is a <em>new</em> SPI surface; the frozen {@code ledger.api}/{@code ledger.spi.HoldView}
 * shapes are unchanged.
 */
public interface LedgerReconView {

    /** All distinct currencies that appear anywhere in the journal. */
    List<String> currencies();

    /**
     * Signed global sum of every journal entry in a currency, in minor units (debits positive,
     * credits negative). For a correct ledger this is always {@code 0} — the seed of the zero-drift
     * proof.
     */
    long globalSignedSumMinor(String currency);

    /**
     * Every posted transaction whose legs do not net to zero in a currency. Empty for a correct
     * ledger; non-empty the instant an unbalanced row is introduced — and strictly stronger than the
     * global sum alone, since two unbalanced transactions that offset still appear here individually.
     */
    List<UnbalancedTransaction> findUnbalancedTransactions();

    /** Distinct accounts touched by the given (offending) transactions, to name implicated accounts. */
    List<UUID> findAccountsForTransactions(Collection<UUID> transactionIds);
}
