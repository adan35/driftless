package io.driftless.partnersim.fault;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.Optional;

/**
 * A per-route fault configuration applied via the control plane. Immutable; replacing a route's
 * profile is how behaviour changes at runtime.
 *
 * @param route the partner route this profile governs (required)
 * @param mode the misbehaviour to inject (required)
 * @param delayMillis base delay for {@code LATENCY} / {@code TIMEOUT} / {@code LATE_RESPONSE}
 * @param jitterMillis upper bound of an extra uniform random delay added to {@code delayMillis}
 * @param probability chance in {@code [0,1]} that {@code DECLINE} / {@code ERROR_RATE} fires
 * @param applyToNext if {@code > 0}, the fault fires deterministically for exactly the next N matching
 *     requests then auto-clears to {@code NONE}; if {@code 0} the profile is sticky
 * @param seed RNG seed making probabilistic modes reproducible for a given request sequence
 * @param declineReason reason returned with a {@code DECLINE} (defaults applied by the engine)
 */
public record FaultProfile(
        @NotNull PartnerRoute route,
        @NotNull FaultMode mode,
        @Min(0) long delayMillis,
        @Min(0) long jitterMillis,
        @DecimalMin("0.0") @DecimalMax("1.0") double probability,
        @Min(0) long applyToNext,
        long seed,
        String declineReason) {

    /** A healthy profile for a route: no injected fault. */
    public static FaultProfile none(PartnerRoute route) {
        return new FaultProfile(route, FaultMode.NONE, 0L, 0L, 0.0, 0L, 0L, null);
    }

    public Optional<String> declineReasonOpt() {
        return Optional.ofNullable(declineReason);
    }

    /** Whether this profile fires for a bounded number of requests rather than indefinitely. */
    public boolean isDeterministicWindow() {
        return applyToNext > 0;
    }

    @AssertTrue(message = "delayMillis must be set (> 0) for LATENCY, TIMEOUT and LATE_RESPONSE modes")
    boolean isDelayConsistent() {
        return switch (mode) {
            case LATENCY, TIMEOUT, LATE_RESPONSE -> delayMillis > 0;
            default -> true;
        };
    }

    @AssertTrue(message = "probability must be > 0 for DECLINE and ERROR_RATE modes")
    boolean isProbabilityConsistent() {
        return switch (mode) {
            case DECLINE, ERROR_RATE -> probability > 0.0;
            default -> true;
        };
    }
}
