package io.driftless.recon.internal.demo;

import io.driftless.recon.internal.fault.FaultRequest;
import io.driftless.recon.internal.fault.FaultRoute;
import java.util.List;
import java.util.Locale;

/**
 * Maps a named demo fault mix to the concrete {@link FaultRequest}s the harness POSTs to the
 * partner-simulator's control plane. Pure and deterministic for a given {@code operations} count and
 * {@code seed}, so a demo run is reproducible.
 *
 * <p>The supported mixes mirror the partner failure modes Spec 03 claims to survive. {@code TIMEOUT}
 * injects a delay that comfortably exceeds the saga's bounded read-timeout, so the bounded-timeout
 * compensating reversal fires; {@code FAIL_BEFORE_RESPONSE} models the partner doing the work then
 * 500-ing; {@code MIXED} blends both. Every mix self-heals to zero drift — that is the whole point.
 */
final class DemoFaultPlan {

    /** Partner delay (ms) for {@code TIMEOUT}; must exceed the saga's bounded read-timeout. */
    static final long TIMEOUT_DELAY_MILLIS = 1_500L;

    private DemoFaultPlan() {}

    /** The fault requests for {@code mix}, scaled to a fraction of {@code operations}. */
    static List<FaultRequest> forMix(String mix, int operations, long seed) {
        String normalized = (mix == null ? "NONE" : mix.trim().toUpperCase(Locale.ROOT));
        return switch (normalized) {
            case "NONE" -> List.of();
            case "TIMEOUT" -> List.of(
                    FaultRequest.timeout(FaultRoute.AUTHORIZE, TIMEOUT_DELAY_MILLIS, share(operations, 0.25)));
            case "FAIL_BEFORE_RESPONSE" -> List.of(
                    FaultRequest.failBeforeResponse(FaultRoute.AUTHORIZE, share(operations, 0.25)));
            case "DECLINE" -> List.of(FaultRequest.decline(FaultRoute.AUTHORIZE, 0.25, seed));
            case "DUPLICATE" -> List.of(FaultRequest.duplicate(FaultRoute.AUTHORIZE, share(operations, 0.25)));
            case "MIXED" -> List.of(
                    FaultRequest.timeout(FaultRoute.AUTHORIZE, TIMEOUT_DELAY_MILLIS, share(operations, 0.2)),
                    FaultRequest.failBeforeResponse(FaultRoute.AUTHORIZE, share(operations, 0.1)));
            default -> throw new IllegalArgumentException("unknown fault mix: " + mix);
        };
    }

    private static long share(int operations, double fraction) {
        return Math.max(1L, Math.round(operations * fraction));
    }
}
