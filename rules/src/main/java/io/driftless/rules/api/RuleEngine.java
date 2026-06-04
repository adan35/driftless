package io.driftless.rules.api;

/**
 * The hot-path authorization decision engine, invoked by the auth saga (Spec 03) before a hold is
 * placed.
 *
 * <p>This interface is the cross-module contract the saga codes against. Implementations evaluate
 * limits, velocity, and MCC rules against an {@link AuthContext} using only pre-compiled, in-memory
 * rule state — never a database query on this call.
 */
public interface RuleEngine {

    /**
     * Decide approve/decline for one authorization.
     *
     * <p>Pure, fast, and deterministic: performs <strong>no I/O</strong>, reads only the in-memory
     * compiled rule set and the supplied {@code context}, and returns the same {@link RuleResult} for
     * any two contexts that are {@code equals}. The first declining rule (in deterministic priority
     * order) wins; if none fires the result is a clean {@link RuleResult#approve()}.
     *
     * @param context the fully-assembled authorization context (carries available balance and the
     *     velocity snapshot, so the engine needs no ledger or store access)
     * @return the decision and, on a decline, the deciding rule id and reason code
     */
    RuleResult evaluate(AuthContext context);
}
