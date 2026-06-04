/**
 * Hot-path limits/velocity/MCC rule engine (Spec 05).
 *
 * <p>The public contract lives in {@link io.driftless.rules.api} ({@code RuleEngine},
 * {@code AuthContext}, {@code RuleResult}, {@code Decision}, {@code VelocitySnapshot}); rules are
 * authored as data in {@link io.driftless.rules.config}, compiled and cached in {@link
 * io.driftless.rules.internal}, and velocity counters live behind the {@link
 * io.driftless.rules.velocity} seam. Evaluation is pure, deterministic, and performs no I/O — the
 * single-digit-millisecond p99 budget is met by reading a pre-compiled, in-memory rule set, never the
 * database.
 */
package io.driftless.rules;
