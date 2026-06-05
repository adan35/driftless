package io.driftless.ledger.internal;

import io.driftless.ledger.internal.persistence.JournalEntryRepository;
import io.driftless.ledger.spi.LedgerReconView;
import io.driftless.ledger.spi.UnbalancedTransaction;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ledger's implementation of the read-only {@link LedgerReconView} SPI. It delegates to the
 * append-only {@link JournalEntryRepository} so reconciliation consumers (Spec 07) sum the real,
 * committed rows through a published contract rather than the ledger's internal persistence.
 *
 * <p>Read-only by construction: every method is a {@code SELECT}; nothing here mutates the journal.
 */
@Component
@RequiredArgsConstructor
public class LedgerReconViewImpl implements LedgerReconView {

    private final JournalEntryRepository entries;

    @Override
    @Transactional(readOnly = true)
    public List<String> currencies() {
        return entries.allCurrencies();
    }

    @Override
    @Transactional(readOnly = true)
    public long globalSignedSumMinor(String currency) {
        return entries.globalSignedSumMinor(currency);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UnbalancedTransaction> findUnbalancedTransactions() {
        return entries.findUnbalancedTransactions();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findAccountsForTransactions(Collection<UUID> transactionIds) {
        return transactionIds.isEmpty() ? List.of() : entries.findAccountsForTransactions(transactionIds);
    }
}
