/**
 * Idempotency keys and the transactional outbox (Spec 02) — the reusable spine that gives every
 * mutating flow its replay guarantee and exactly-once event delivery.
 *
 * <p>The public contracts live in {@link io.driftless.idempotency.api} (the replay guard) and {@link
 * io.driftless.outbox.api} (the outbox writer); their DB-backed implementations live in the
 * respective {@code internal} packages. Callers depend on the {@code api} packages only.
 */
package io.driftless.idempotency;
