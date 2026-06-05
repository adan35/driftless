package io.driftless.observability.metrics;

import io.driftless.outbox.spi.OutboxReconView;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Binds the outbox relay-health gauge to the published {@link OutboxReconView} SPI: the current
 * PENDING queue depth, read lazily at scrape time. A healthy relay keeps this near zero; a sustained
 * rise is the dashboard's early warning of a wedged or lagging relay, well before any event becomes
 * "stuck" enough to fail reconciliation.
 */
@Component
public class OutboxMetrics {

    public OutboxMetrics(MeterRegistry registry, OutboxReconView outboxRecon) {
        Gauge.builder(MetricNames.OUTBOX_PENDING_DEPTH, outboxRecon, OutboxReconView::countPending)
                .description("PENDING outbox events awaiting relay")
                .register(registry);
    }
}
