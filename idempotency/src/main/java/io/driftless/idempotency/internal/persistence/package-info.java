/**
 * JPA persistence for the idempotency replay guard (Spec 02): the {@code idempotency_record} entity,
 * its composite {@code (scope, key)} id, its status enum, and the repository.
 *
 * <p>Internal persistence details that never cross the {@code io.driftless.idempotency.api} boundary.
 * The unique-constrained composite key is the database-level backstop that makes a concurrent
 * duplicate collide rather than run the guarded operation twice.
 */
package io.driftless.idempotency.internal.persistence;
