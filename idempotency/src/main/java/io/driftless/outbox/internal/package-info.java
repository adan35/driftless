/**
 * DB-backed implementation of the transactional outbox (Spec 02).
 *
 * <p>{@code OutboxWriterService} appends events in the caller's transaction; {@code OutboxRelay} (a
 * scheduled sweep) and {@code OutboxBatchPublisher} (one transaction per batch) deliver them exactly
 * once to a pluggable {@link io.driftless.outbox.internal.EventPublisher}, crash-safely and in
 * per-aggregate order. Internal to the module — callers depend on {@code io.driftless.outbox.api}.
 */
package io.driftless.outbox.internal;
