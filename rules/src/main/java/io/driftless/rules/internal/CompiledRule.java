package io.driftless.rules.internal;

import io.driftless.rules.api.AuthContext;
import io.driftless.rules.api.RuleResult;
import java.util.Optional;

/**
 * One rule in its evaluable, in-memory form — the output of compiling an authored {@link
 * io.driftless.rules.config.RuleProperties} entry. Implementations are immutable and side-effect
 * free, so they can be shared across threads and evaluated on the hot path with no locking.
 *
 * <p>Compiling thresholds once (here) keeps {@link #test} a handful of integer comparisons rather
 * than re-parsing config per authorization — that is the performance point of Spec 05.
 */
sealed interface CompiledRule
        permits CompiledRule.PerTransactionLimit,
                CompiledRule.PeriodicSpendCap,
                CompiledRule.VelocityCount,
                CompiledRule.VelocityAmount,
                CompiledRule.MccBlockList,
                CompiledRule.MccAllowList {

    /** Stable identifier of the authored rule, surfaced as {@code RuleResult.ruleId} on a decline. */
    String ruleId();

    /**
     * Evaluation order: lower runs first, so the highest-priority declining rule wins deterministically.
     * Ties break on {@link #ruleId()} for a total, stable ordering.
     */
    int priority();

    /**
     * Apply this rule to a context.
     *
     * @return a declining {@link RuleResult} if the rule fires, otherwise {@link Optional#empty()}
     */
    Optional<RuleResult> test(AuthContext context);

    /**
     * Declines when a single authorization exceeds {@code maxAmountMinor} (integer minor-unit
     * comparison — no floating point).
     */
    record PerTransactionLimit(String ruleId, int priority, long maxAmountMinor) implements CompiledRule {
        @Override
        public Optional<RuleResult> test(AuthContext context) {
            return context.amount().amountMinor() > maxAmountMinor
                    ? Optional.of(RuleResult.decline(ruleId, ReasonCodes.LIMIT_EXCEEDED))
                    : Optional.empty();
        }
    }

    /**
     * Declines when the windowed spend so far plus this authorization would exceed {@code
     * capAmountMinor}. Spend-so-far is read from the velocity snapshot (in-memory), never aggregated
     * from the DB on the hot path.
     */
    record PeriodicSpendCap(String ruleId, int priority, long capAmountMinor) implements CompiledRule {
        @Override
        public Optional<RuleResult> test(AuthContext context) {
            long projected = Math.addExact(
                    context.velocity().total().amountMinor(), context.amount().amountMinor());
            return projected > capAmountMinor
                    ? Optional.of(RuleResult.decline(ruleId, ReasonCodes.LIMIT_EXCEEDED))
                    : Optional.empty();
        }
    }

    /**
     * Declines when accepting this authorization would push the count in the rolling window past
     * {@code maxCount} (i.e. the window already holds {@code >= maxCount}).
     */
    record VelocityCount(String ruleId, int priority, int maxCount) implements CompiledRule {
        @Override
        public Optional<RuleResult> test(AuthContext context) {
            return context.velocity().count() >= maxCount
                    ? Optional.of(RuleResult.decline(ruleId, ReasonCodes.VELOCITY))
                    : Optional.empty();
        }
    }

    /**
     * Declines when the summed amount in the rolling window plus this authorization would exceed
     * {@code maxAmountMinor}. Distinct reason from {@link PeriodicSpendCap} only in intent; both are
     * windowed sums — this one is authored under the velocity rule type.
     */
    record VelocityAmount(String ruleId, int priority, long maxAmountMinor) implements CompiledRule {
        @Override
        public Optional<RuleResult> test(AuthContext context) {
            long projected = Math.addExact(
                    context.velocity().total().amountMinor(), context.amount().amountMinor());
            return projected > maxAmountMinor
                    ? Optional.of(RuleResult.decline(ruleId, ReasonCodes.VELOCITY))
                    : Optional.empty();
        }
    }

    /** Declines when the context MCC is in the blocked set. */
    record MccBlockList(String ruleId, int priority, java.util.Set<String> blocked) implements CompiledRule {
        @Override
        public Optional<RuleResult> test(AuthContext context) {
            return blocked.contains(context.mcc())
                    ? Optional.of(RuleResult.decline(ruleId, ReasonCodes.MCC_BLOCKED))
                    : Optional.empty();
        }
    }

    /** Declines when the context MCC is <em>not</em> in the allowed set (default-deny by category). */
    record MccAllowList(String ruleId, int priority, java.util.Set<String> allowed) implements CompiledRule {
        @Override
        public Optional<RuleResult> test(AuthContext context) {
            return allowed.contains(context.mcc())
                    ? Optional.empty()
                    : Optional.of(RuleResult.decline(ruleId, ReasonCodes.MCC_BLOCKED));
        }
    }
}
