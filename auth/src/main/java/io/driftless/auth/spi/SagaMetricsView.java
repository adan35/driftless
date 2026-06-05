package io.driftless.auth.spi;

import io.driftless.auth.api.AuthorizationStatus;

/**
 * A published, read-only window onto the saga's operational counters, for cross-cutting observability
 * (Spec 08) to bind Micrometer gauges to <em>without</em> reaching into the saga's internal classes.
 *
 * <p>Every figure here is a health signal, not money math: a non-zero {@link
 * #danglingPartnerReverseFinalizations()} flags a possible phantom partner-side authorization, {@link
 * #outstandingPartnerObligations()} is the live count of compensations still owed a partner reverse,
 * and {@link #authorizationsInStatus(AuthorizationStatus)} feeds the saga's state-distribution panel.
 * It is strictly read-only: observing the saga never moves it.
 */
public interface SagaMetricsView {

    /**
     * Compensations that reached terminal {@code REVERSED} without a confirmed partner reverse. Must
     * read zero — a non-zero value is the dashboard's red flag for a dangling partner authorization.
     */
    long danglingPartnerReverseFinalizations();

    /** Authorizations still {@code COMPENSATING}: outstanding partner-reverse obligations in flight. */
    long outstandingPartnerObligations();

    /** Count of authorizations currently in {@code status} — the saga state-distribution gauge source. */
    long authorizationsInStatus(AuthorizationStatus status);
}
