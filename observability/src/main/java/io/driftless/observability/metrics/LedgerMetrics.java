package io.driftless.observability.metrics;

import io.driftless.ledger.spi.LedgerReconView;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Binds the ledger size / zero-drift gauges to the published {@link LedgerReconView} SPI.
 *
 * <p>Each gauge is a read-only summation over committed {@code journal_entry} rows, evaluated lazily
 * at scrape time through the SPI (never the ledger's internals): the total entry count, and the
 * absolute signed sum across currencies — which is {@code 0} for a correct ledger and is therefore a
 * second, independent witness of zero drift alongside the reconciliation gauge.
 */
@Component
public class LedgerMetrics {

    private final LedgerReconView ledgerRecon;

    public LedgerMetrics(MeterRegistry registry, LedgerReconView ledgerRecon) {
        this.ledgerRecon = ledgerRecon;
        Gauge.builder(MetricNames.LEDGER_ENTRY_COUNT, ledgerRecon, LedgerReconView::entryCount)
                .description("Total committed journal_entry rows")
                .register(registry);
        Gauge.builder(MetricNames.LEDGER_SIGNED_SUM_ABS, this, LedgerMetrics::absoluteSignedSumMinor)
                .description("Sum over currencies of |signed journal sum| in minor units; 0 for a correct ledger")
                .baseUnit("minor_units")
                .register(registry);
    }

    /** Σ over currencies of |signed sum|; {@code 0} for a balanced ledger, non-zero the instant drift appears. */
    double absoluteSignedSumMinor() {
        long total = 0L;
        for (String currency : ledgerRecon.currencies()) {
            total += Math.abs(ledgerRecon.globalSignedSumMinor(currency));
        }
        return (double) total;
    }
}
