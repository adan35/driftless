/**
 * Tokenization lifecycle (Spec 06): maps a card reference to wallet/device tokens and drives each
 * token through a strict {@link io.driftless.tokens.api.TokenTransition} state machine.
 *
 * <p>The published contract is {@link io.driftless.tokens.api.TokenService} over {@link
 * io.driftless.tokens.api.Token} / {@link io.driftless.tokens.api.TokenStatus}; the DB-backed engine
 * lives in {@code io.driftless.tokens.internal} and the thin REST surface in {@code
 * io.driftless.tokens.web}. Every transition is guarded by the Spec 02 idempotency guard and
 * propagates a {@code TokenStatusChanged} event through the Spec 02 transactional outbox, atomically
 * with the status write; the {@code token_status_history} audit trail is append-only.
 */
package io.driftless.tokens;
