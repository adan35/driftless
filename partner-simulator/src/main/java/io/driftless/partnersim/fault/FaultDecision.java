package io.driftless.partnersim.fault;

import java.util.Optional;

/**
 * The resolved instruction for a single request, computed by the {@link FaultRegistry} from the
 * route's active {@link FaultProfile}. The {@code partner} service reads this to decide how to behave;
 * it carries no money or ledger semantics.
 *
 * @param mode the effective mode for this request (probabilistic modes already rolled)
 * @param delayMillis the concrete delay to sleep (base + resolved jitter), or 0 for none
 * @param declineReason a decline reason when {@code mode == DECLINE}
 */
public record FaultDecision(FaultMode mode, long delayMillis, String declineReason) {

    private static final FaultDecision NORMAL = new FaultDecision(FaultMode.NONE, 0L, null);

    /** No fault: behave normally with no delay. */
    public static FaultDecision normal() {
        return NORMAL;
    }

    public static FaultDecision delayed(FaultMode mode, long delayMillis) {
        return new FaultDecision(mode, delayMillis, null);
    }

    public static FaultDecision decline(String reason) {
        return new FaultDecision(FaultMode.DECLINE, 0L, reason);
    }

    public static FaultDecision of(FaultMode mode) {
        return new FaultDecision(mode, 0L, null);
    }

    public Optional<String> declineReasonOpt() {
        return Optional.ofNullable(declineReason);
    }
}
