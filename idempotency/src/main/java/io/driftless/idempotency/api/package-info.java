/**
 * The frozen public contract for the idempotency replay guard (Spec 02).
 *
 * <p>Mutating flows depend on {@link io.driftless.idempotency.api.IdempotencyGuard} only; the
 * DB-backed implementation lives in {@code io.driftless.idempotency.internal}. A retry of the same
 * request (same key + same request hash) returns the original {@link
 * io.driftless.idempotency.api.IdempotentResult} with {@code replayed=true}; a key reused with a
 * different body raises {@link io.driftless.idempotency.api.IdempotencyConflict}.
 */
package io.driftless.idempotency.api;
