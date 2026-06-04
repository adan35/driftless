/**
 * DB-backed implementation of the idempotency replay guard (Spec 02).
 *
 * <p>{@code IdempotencyGuardService} runs each guarded operation at most once per key inside a single
 * transaction, with the unique-constrained {@code idempotency_record} as the concurrency backstop;
 * the persistence types live in {@code io.driftless.idempotency.internal.persistence}. Internal to
 * the module — callers depend on {@code io.driftless.idempotency.api} only.
 */
package io.driftless.idempotency.internal;
