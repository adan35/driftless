package io.driftless.rules.api;

import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of {@link RuleEngine#evaluate}: a {@link Decision} and, on a {@link Decision#DECLINE},
 * which rule decided and a machine-readable reason.
 *
 * <p>On a clean {@link Decision#APPROVE} both {@code ruleId} and {@code reasonCode} are {@code null}
 * — no single rule "approves", the absence of any declining rule does. On a {@link Decision#DECLINE}
 * both are present (the first rule that fired, in deterministic priority order).
 *
 * @param decision approve or decline
 * @param ruleId identifier of the deciding rule on a decline; {@code null} on a clean approve
 * @param reasonCode machine-readable reason on a decline (e.g. {@code LIMIT_EXCEEDED}, {@code
 *     VELOCITY}, {@code MCC_BLOCKED}); {@code null} on a clean approve
 */
public record RuleResult(Decision decision, String ruleId, String reasonCode) {

    public RuleResult {
        Objects.requireNonNull(decision, "decision");
        if (decision == Decision.DECLINE && (reasonCode == null || reasonCode.isBlank())) {
            throw new IllegalArgumentException("a DECLINE must carry a non-blank reasonCode");
        }
    }

    /** A clean approval carrying no deciding rule. */
    public static RuleResult approve() {
        return new RuleResult(Decision.APPROVE, null, null);
    }

    /** A decline attributed to {@code ruleId} with the given machine-readable {@code reasonCode}. */
    public static RuleResult decline(String ruleId, String reasonCode) {
        return new RuleResult(Decision.DECLINE, ruleId, reasonCode);
    }

    /** {@code true} when the authorization was approved. */
    public boolean isApproved() {
        return decision == Decision.APPROVE;
    }

    /** The deciding rule id wrapped for absence-safe access; empty on a clean approve. */
    public Optional<String> decidingRuleId() {
        return Optional.ofNullable(ruleId);
    }

    /** The machine-readable reason wrapped for absence-safe access; empty on a clean approve. */
    public Optional<String> reason() {
        return Optional.ofNullable(reasonCode);
    }
}
