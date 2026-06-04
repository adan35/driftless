/**
 * Engine internals (Spec 05): authored rules compiled into an immutable in-memory {@link
 * io.driftless.rules.internal.CompiledRuleSet}, held in a {@link io.driftless.rules.internal.RuleCache}
 * refreshed off the hot path, and evaluated by {@link io.driftless.rules.internal.RuleEvaluator} — the
 * pure, I/O-free implementation of the public {@link io.driftless.rules.api.RuleEngine}. Nothing here
 * holds a datasource: the hot path is in-memory by construction.
 */
package io.driftless.rules.internal;
