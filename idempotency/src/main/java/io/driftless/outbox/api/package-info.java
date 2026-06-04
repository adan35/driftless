/**
 * The frozen public contract for the transactional outbox (Spec 02).
 *
 * <p>Callers append an {@link io.driftless.outbox.api.OutboxEvent} via {@link
 * io.driftless.outbox.api.OutboxWriter} inside the same transaction as their state change; a
 * crash-safe relay (in {@code io.driftless.outbox.internal}) publishes appended events exactly once.
 * The relay and its publisher seam are deliberately swappable so a real message broker can slot in
 * later without changing this contract.
 */
package io.driftless.outbox.api;
