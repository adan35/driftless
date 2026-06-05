package io.driftless.recon.internal.fault;

/**
 * The control-plane request body the harness POSTs to {@code /control/faults}. Its field names match
 * the partner-simulator's {@code FaultProfile} exactly so it binds on the wire without a shared type.
 *
 * @param route the partner route this profile governs
 * @param mode the misbehaviour to inject
 * @param delayMillis base delay for {@code LATENCY} / {@code TIMEOUT} / {@code LATE_RESPONSE}
 * @param jitterMillis upper bound of an extra uniform random delay added to {@code delayMillis}
 * @param probability chance in {@code [0,1]} that {@code DECLINE} / {@code ERROR_RATE} fires
 * @param applyToNext if {@code > 0}, fire deterministically for exactly the next N requests then
 *     auto-clear; if {@code 0} the profile is sticky
 * @param seed RNG seed making probabilistic modes reproducible
 * @param declineReason reason returned with a {@code DECLINE}
 */
public record FaultRequest(
        FaultRoute route,
        FaultKind mode,
        long delayMillis,
        long jitterMillis,
        double probability,
        long applyToNext,
        long seed,
        String declineReason) {

    /** A latency injection of {@code delayMillis} on a route, sticky until reset. */
    public static FaultRequest latency(FaultRoute route, long delayMillis) {
        return new FaultRequest(route, FaultKind.LATENCY, delayMillis, 0L, 0.0, 0L, 0L, null);
    }

    /** A bounded-timeout breach on a route for the next {@code count} requests. */
    public static FaultRequest timeout(FaultRoute route, long delayMillis, long count) {
        return new FaultRequest(route, FaultKind.TIMEOUT, delayMillis, 0L, 0.0, count, 0L, null);
    }

    /** A fail-before-response (side effect performed, then 5xx) for the next {@code count} requests. */
    public static FaultRequest failBeforeResponse(FaultRoute route, long count) {
        return new FaultRequest(route, FaultKind.FAIL_BEFORE_RESPONSE, 0L, 0L, 0.0, count, 0L, null);
    }

    /** A duplicate-response marker for the next {@code count} requests. */
    public static FaultRequest duplicate(FaultRoute route, long count) {
        return new FaultRequest(route, FaultKind.DUPLICATE, 0L, 0L, 0.0, count, 0L, null);
    }

    /** A probabilistic decline on a route, reproducible for {@code seed}. */
    public static FaultRequest decline(FaultRoute route, double probability, long seed) {
        return new FaultRequest(route, FaultKind.DECLINE, 0L, 0L, probability, 0L, seed, null);
    }

    /** A probabilistic 5xx error on a route, reproducible for {@code seed}. */
    public static FaultRequest errorRate(FaultRoute route, double probability, long seed) {
        return new FaultRequest(route, FaultKind.ERROR_RATE, 0L, 0L, probability, 0L, seed, null);
    }
}
