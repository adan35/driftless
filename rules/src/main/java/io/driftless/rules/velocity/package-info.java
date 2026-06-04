/**
 * Velocity counters (Spec 05): the {@link io.driftless.rules.velocity.VelocityStore} seam and its
 * in-memory MVP. Recording is idempotent per authorization id (a retried auth counts once) and
 * snapshots answer from an in-memory rolling window — never a per-authorization DB aggregation. A
 * Redis/persistence implementation can later replace the in-memory store behind the same interface.
 */
package io.driftless.rules.velocity;
