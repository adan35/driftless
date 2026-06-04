package io.driftless.tokens.api;

import io.driftless.common.id.TokenId;

/**
 * The tokenization lifecycle service (Spec 06): maps a card to tokens and drives each token through
 * the strict {@link TokenTransition} state machine, propagating every status change via the Spec 02
 * transactional outbox.
 *
 * <p>Every mutating method is <strong>idempotent</strong> and runs through the Spec 02 {@code
 * IdempotencyGuard}: a retried call returns the same {@link Token} and produces no duplicate status
 * row or event. With no caller-supplied idempotency key (the case for these interface methods, e.g.
 * an internal caller such as the auth saga), a transition whose token is already in the target state
 * is a safe no-op replay returning the current token, not an {@link IllegalTokenTransition}; any
 * transition that is neither legal nor a no-op is rejected with {@link IllegalTokenTransition} and
 * changes nothing.
 *
 * <p>The web layer reuses the same engine but supplies the caller's {@code Idempotency-Key}, under
 * which the strict state machine applies (so, for example, a fresh {@code resume} of an already-{@code
 * ACTIVE} token is an {@link IllegalTokenTransition}, while a retry under the original key replays).
 */
public interface TokenService {

    /**
     * Provision a new token for the command's card reference, born {@link TokenStatus#INACTIVE}.
     *
     * @param cmd the create command (log-safe {@code cardRef}, optional caller-supplied {@link
     *     TokenId} that anchors idempotent creation)
     * @return the created token in {@link TokenStatus#INACTIVE}
     */
    Token create(CreateTokenCommand cmd);

    /**
     * {@link TokenStatus#INACTIVE} → {@link TokenStatus#ACTIVE}.
     *
     * @throws IllegalTokenTransition if the token is in any state from which activation is illegal
     */
    Token activate(TokenId id);

    /**
     * {@link TokenStatus#ACTIVE} → {@link TokenStatus#SUSPENDED}.
     *
     * @throws IllegalTokenTransition if the token is in any state from which suspension is illegal
     */
    Token suspend(TokenId id);

    /**
     * {@link TokenStatus#SUSPENDED} → {@link TokenStatus#ACTIVE}.
     *
     * @throws IllegalTokenTransition if the token is in any state from which resumption is illegal
     */
    Token resume(TokenId id);

    /**
     * Any non-terminal state → terminal {@link TokenStatus#DEACTIVATED}.
     *
     * @throws IllegalTokenTransition if the token is already {@link TokenStatus#DEACTIVATED} under a
     *     caller-supplied key (a keyless retry of an already-deactivated token is a no-op replay)
     */
    Token deactivate(TokenId id);

    /**
     * Read a token's current state — the status the auth saga gates authorizations on.
     *
     * @param id the token identifier
     * @return the current {@link Token}
     * @throws TokenNotFound if no token with {@code id} exists
     */
    Token find(TokenId id);
}
