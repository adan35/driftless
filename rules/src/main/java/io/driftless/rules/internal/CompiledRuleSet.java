package io.driftless.rules.internal;

import io.driftless.rules.api.AuthContext;
import io.driftless.rules.api.RuleResult;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * An immutable, pre-sorted snapshot of every active {@link CompiledRule} — the unit the cache swaps
 * atomically and the only state {@link RuleEvaluator#evaluate} reads on the hot path.
 *
 * <p>Rules are sorted once at construction (by {@link CompiledRule#priority()}, then {@code ruleId}
 * for a stable total order), so evaluation is a single in-order scan that stops at the first decline.
 * Because the list is unmodifiable and the rules are side-effect free, one instance is safely shared
 * across all authorization threads with no synchronization.
 */
record CompiledRuleSet(List<CompiledRule> rules) {

    CompiledRuleSet {
        rules = rules.stream()
                .sorted(Comparator.comparingInt(CompiledRule::priority).thenComparing(CompiledRule::ruleId))
                .toList();
    }

    /** An empty rule set — approves everything. The cache's safe initial and fallback value. */
    static CompiledRuleSet empty() {
        return new CompiledRuleSet(List.of());
    }

    /**
     * Evaluate the context against every rule in priority order, returning the first decline or a
     * clean approve. Pure and allocation-light: the loop touches only the supplied context and the
     * pre-compiled rules.
     */
    RuleResult evaluate(AuthContext context) {
        for (CompiledRule rule : rules) {
            Optional<RuleResult> fired = rule.test(context);
            if (fired.isPresent()) {
                return fired.get();
            }
        }
        return RuleResult.approve();
    }

    /** Number of active rules — for logging/metrics on refresh. */
    int size() {
        return rules.size();
    }
}
