package io.driftless.observability.metrics;

import io.driftless.common.id.AccountId;
import io.driftless.common.id.TxId;
import io.driftless.ledger.api.Account;
import io.driftless.ledger.api.Balance;
import io.driftless.ledger.api.BalanceInvariantViolation;
import io.driftless.ledger.api.JournalEntry;
import io.driftless.ledger.api.Ledger;
import io.driftless.ledger.api.Page;
import io.driftless.ledger.api.PostingRequest;
import io.driftless.ledger.api.PostingResult;
import io.driftless.ledger.api.Transaction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;

/**
 * A transparent metrics decorator over the frozen {@link Ledger} contract: it counts accepted posts
 * and rejected-unbalanced posts on the write path, then delegates unchanged. It does not alter the
 * ledger's shape, transactions or behaviour — every call passes straight through to the real {@code
 * LedgerService}, which keeps its own {@code @Transactional} boundary.
 *
 * <p>Wired as the {@code @Primary} {@link Ledger} (see {@code ObservabilityMetricsConfig}) so the
 * whole monolith posts through it without any feature module knowing the meter exists — the ledger
 * module stays clean.
 */
public class MeteredLedger implements Ledger {

    private final Ledger delegate;
    private final Counter postings;
    private final Counter rejected;

    public MeteredLedger(Ledger delegate, MeterRegistry registry) {
        this.delegate = delegate;
        this.postings = Counter.builder(MetricNames.LEDGER_POSTINGS)
                .description("Balanced posts accepted by the ledger")
                .register(registry);
        this.rejected = Counter.builder(MetricNames.LEDGER_REJECTED)
                .description("Posts rejected with BalanceInvariantViolation (nothing written)")
                .register(registry);
    }

    @Override
    public PostingResult post(PostingRequest request) {
        try {
            PostingResult result = delegate.post(request);
            postings.increment();
            return result;
        } catch (BalanceInvariantViolation e) {
            rejected.increment();
            throw e;
        }
    }

    @Override
    public Account openAccount(Account account) {
        return delegate.openAccount(account);
    }

    @Override
    public Balance balanceOf(AccountId account) {
        return delegate.balanceOf(account);
    }

    @Override
    public Transaction findTransaction(TxId id) {
        return delegate.findTransaction(id);
    }

    @Override
    public List<JournalEntry> entriesFor(AccountId account, Page page) {
        return delegate.entriesFor(account, page);
    }
}
