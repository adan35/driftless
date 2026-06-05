package io.driftless.recon.api;

/**
 * Throughput + latency metrics recorded by a {@link io.driftless.recon.internal.load.LoadGenerator}
 * run, ready to be surfaced as Micrometer values on Spec 08's dashboard.
 *
 * <p>Latencies are wall-clock per authorize operation, in milliseconds. {@code failed} counts
 * operations that raised an unexpected error (a partner-induced compensation is a normal outcome,
 * not a failure).
 *
 * @param totalOperations authorize operations attempted
 * @param approved authorizations that ended AUTHORIZED (or were later captured)
 * @param declined authorizations declined by a rule / token / partner
 * @param compensated authorizations that compensated after a partner timeout/failure
 * @param captures successful capture follow-ups issued
 * @param reversals successful reverse follow-ups issued
 * @param failed operations that raised an unexpected error
 * @param elapsedMillis total wall-clock duration of the run
 * @param throughputPerSecond achieved throughput (operations / elapsed seconds)
 * @param p50LatencyMillis median per-operation latency
 * @param p95LatencyMillis 95th-percentile per-operation latency
 * @param p99LatencyMillis 99th-percentile per-operation latency
 * @param maxLatencyMillis worst observed per-operation latency
 */
public record LoadResult(
        int totalOperations,
        int approved,
        int declined,
        int compensated,
        int captures,
        int reversals,
        int failed,
        long elapsedMillis,
        double throughputPerSecond,
        long p50LatencyMillis,
        long p95LatencyMillis,
        long p99LatencyMillis,
        long maxLatencyMillis) {}
