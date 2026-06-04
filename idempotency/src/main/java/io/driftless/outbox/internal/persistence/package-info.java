/**
 * JPA persistence for the transactional outbox (Spec 02): the {@code outbox_event} entity, its status
 * enum, and the repository whose {@code FOR UPDATE SKIP LOCKED} claim feeds the relay.
 *
 * <p>Internal persistence details that never cross the {@code io.driftless.outbox.api} boundary. The
 * monotonic identity id both orders delivery and keys idempotent consumers.
 */
package io.driftless.outbox.internal.persistence;
