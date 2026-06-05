/**
 * Cross-cutting Micrometer instrumentation for Driftless (Spec 08).
 *
 * <p>This package is the single home for system-wide metrics. It reads each flow module through its
 * published {@code api}/{@code spi} and the outbox event stream — never a sibling's internals — and
 * exposes everything under the {@code driftless_} Prometheus prefix (see {@link
 * io.driftless.observability.metrics.MetricNames}):
 *
 * <ul>
 *   <li>{@link io.driftless.observability.metrics.ReconMetrics} — the zero-drift gauges, driven by the
 *       reconciliation result application event.
 *   <li>{@link io.driftless.observability.metrics.TimedRuleEngine} — a {@code @Primary} decorator that
 *       times {@code RuleEngine.evaluate} with a percentile histogram (the p99 claim).
 *   <li>{@link io.driftless.observability.metrics.MeteredLedger} — a {@code @Primary} decorator that
 *       counts accepted and rejected-unbalanced posts.
 *   <li>{@link io.driftless.observability.metrics.OutboxEventMetrics} — maps the relay's published
 *       domain events onto counters with exactly-once (id-deduped) increments.
 *   <li>{@link io.driftless.observability.metrics.LedgerMetrics}, {@link
 *       io.driftless.observability.metrics.AuthMetrics}, {@link
 *       io.driftless.observability.metrics.OutboxMetrics} — gauges bound to the published read SPIs.
 * </ul>
 */
package io.driftless.observability.metrics;
