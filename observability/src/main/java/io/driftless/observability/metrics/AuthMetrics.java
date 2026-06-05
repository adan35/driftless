package io.driftless.observability.metrics;

import io.driftless.auth.api.AuthorizationStatus;
import io.driftless.auth.spi.SagaMetricsView;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Binds the auth-saga operational gauges to the published {@link SagaMetricsView} SPI: the
 * must-be-zero dangling-partner-reverse signal, the live count of outstanding partner-reverse
 * obligations, and the saga state distribution (one gauge per {@link AuthorizationStatus}). All are
 * read lazily at scrape time through the SPI, never the saga's internals.
 */
@Component
public class AuthMetrics {

    public AuthMetrics(MeterRegistry registry, SagaMetricsView saga) {
        Gauge.builder(
                        MetricNames.AUTH_DANGLING_PARTNER_REVERSE,
                        saga,
                        SagaMetricsView::danglingPartnerReverseFinalizations)
                .description("Compensations finalized REVERSED without a confirmed partner reverse; must be 0")
                .register(registry);
        Gauge.builder(MetricNames.AUTH_OUTSTANDING_OBLIGATIONS, saga, SagaMetricsView::outstandingPartnerObligations)
                .description("Authorizations still COMPENSATING (outstanding partner reverses)")
                .register(registry);
        for (AuthorizationStatus status : AuthorizationStatus.values()) {
            Gauge.builder(MetricNames.AUTH_SAGA_STATE, saga, s -> (double) s.authorizationsInStatus(status))
                    .tag(MetricNames.TAG_STATUS, status.name())
                    .description("Authorization saga state distribution")
                    .register(registry);
        }
    }
}
