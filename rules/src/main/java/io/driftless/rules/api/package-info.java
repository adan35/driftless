/**
 * Public contract of the hot-path rule engine (Spec 05), called by the auth saga (Spec 03).
 *
 * <p>The shapes here — {@link io.driftless.rules.api.AuthContext}, {@link
 * io.driftless.rules.api.Decision}, {@link io.driftless.rules.api.RuleResult}, {@link
 * io.driftless.rules.api.VelocitySnapshot}, and the {@link io.driftless.rules.api.RuleEngine}
 * interface — are what downstream code depends on. The engine evaluates these inputs against a
 * pre-compiled, in-memory rule set with no I/O on the call, meeting a single-digit-millisecond p99
 * budget.
 */
package io.driftless.rules.api;
