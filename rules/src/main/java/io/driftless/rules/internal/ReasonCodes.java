package io.driftless.rules.internal;

/**
 * The machine-readable reason codes a {@link io.driftless.rules.api.RuleResult} carries on a decline.
 * These are part of the engine's observable contract — the saga, dashboards, and tests match on them
 * — so they are defined once here rather than scattered as string literals.
 */
public final class ReasonCodes {

    /** A limit rule declined: per-transaction max or periodic spend cap exceeded. */
    public static final String LIMIT_EXCEEDED = "LIMIT_EXCEEDED";

    /** A velocity rule declined: too many, or too much, in the rolling window. */
    public static final String VELOCITY = "VELOCITY";

    /** An MCC rule declined: the merchant category is blocked (or not allow-listed). */
    public static final String MCC_BLOCKED = "MCC_BLOCKED";

    private ReasonCodes() {}
}
