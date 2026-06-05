package io.driftless.recon.api;

import java.util.Objects;

/**
 * A configurable description of the synthetic traffic the {@link
 * io.driftless.recon.internal.load.LoadGenerator} drives at the saga: how many operations, how
 * concurrently, and the mix of outcomes and amounts.
 *
 * <p>All amounts are integer minor units. Probabilities are in {@code [0,1]}. The distributions are
 * deterministic for a given {@code seed}, so a load run is reproducible.
 *
 * @param operations total number of authorize operations to issue
 * @param concurrency number of worker threads issuing operations in parallel
 * @param targetPerSecond soft upper bound on issue rate (operations/second); {@code <= 0} means
 *     unthrottled
 * @param declineRatio fraction of authorizations steered to a guaranteed rule decline (a blocked
 *     MCC), in {@code [0,1]}
 * @param captureRatio of the authorizations that are approved, the fraction that are then captured
 *     (the remainder of follow-ups are reversed), in {@code [0,1]}
 * @param reverseRatio of the approved authorizations, the fraction that are reversed instead of
 *     left open, in {@code [0,1]}
 * @param minAmountMinor inclusive lower bound of the per-authorization amount, in minor units
 * @param maxAmountMinor inclusive upper bound of the per-authorization amount, in minor units
 * @param seed RNG seed making the whole mix reproducible
 */
public record LoadProfile(
        int operations,
        int concurrency,
        int targetPerSecond,
        double declineRatio,
        double captureRatio,
        double reverseRatio,
        long minAmountMinor,
        long maxAmountMinor,
        long seed) {

    public LoadProfile {
        if (operations < 0) {
            throw new IllegalArgumentException("operations must be >= 0");
        }
        if (concurrency < 1) {
            throw new IllegalArgumentException("concurrency must be >= 1");
        }
        requireRatio(declineRatio, "declineRatio");
        requireRatio(captureRatio, "captureRatio");
        requireRatio(reverseRatio, "reverseRatio");
        if (minAmountMinor < 1 || maxAmountMinor < minAmountMinor) {
            throw new IllegalArgumentException("require 1 <= minAmountMinor <= maxAmountMinor");
        }
    }

    private static void requireRatio(double value, String name) {
        if (value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be in [0,1]");
        }
    }

    /** A balanced default mix: mostly approvals, a healthy capture/reverse split, small amounts. */
    public static LoadProfile defaults(int operations, int concurrency, long seed) {
        return new LoadProfile(operations, concurrency, 0, 0.2, 0.6, 0.25, 1_000L, 50_000L, seed);
    }

    /** This profile with a different operation count (everything else preserved). */
    public LoadProfile withOperations(int newOperations) {
        return new LoadProfile(
                newOperations,
                concurrency,
                targetPerSecond,
                declineRatio,
                captureRatio,
                reverseRatio,
                minAmountMinor,
                maxAmountMinor,
                seed);
    }

    public boolean throttled() {
        return targetPerSecond > 0;
    }

    @Override
    public String toString() {
        return Objects.toString(
                "LoadProfile[operations=%d, concurrency=%d, targetPerSecond=%d, declineRatio=%.2f, captureRatio=%.2f, reverseRatio=%.2f, amount=[%d,%d], seed=%d]"
                        .formatted(
                                operations,
                                concurrency,
                                targetPerSecond,
                                declineRatio,
                                captureRatio,
                                reverseRatio,
                                minAmountMinor,
                                maxAmountMinor,
                                seed));
    }
}
