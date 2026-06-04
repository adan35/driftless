package io.driftless.tokens.internal;

import io.driftless.common.id.TokenId;
import io.driftless.tokens.api.CreateTokenCommand;
import io.driftless.tokens.api.IllegalTokenTransition;
import io.driftless.tokens.api.Token;

/**
 * Module-internal command surface that mirrors the public {@code TokenService} but threads the
 * caller's {@code Idempotency-Key} through each mutating operation.
 *
 * <p>The frozen public {@code TokenService} is keyless (used by internal callers such as the auth
 * saga, where idempotency is anchored on the token's resulting state). The web layer instead reads an
 * explicit {@code Idempotency-Key} header and calls these methods so the Spec 02 guard keys replay on
 * the caller's key: a retry under the same key replays the original result, the same key with a
 * different body is a conflict, and under the key the strict state machine applies (a fresh illegal
 * transition raises {@link IllegalTokenTransition}). It is not part of the published api and must not
 * be depended on across module boundaries.
 */
public interface KeyedTokenService {

    /** Idempotent create under the caller's key. */
    Token create(CreateTokenCommand cmd, String idempotencyKey);

    /** Idempotent activate under the caller's key. */
    Token activate(TokenId id, String idempotencyKey);

    /** Idempotent suspend under the caller's key. */
    Token suspend(TokenId id, String idempotencyKey);

    /** Idempotent resume under the caller's key. */
    Token resume(TokenId id, String idempotencyKey);

    /** Idempotent deactivate under the caller's key. */
    Token deactivate(TokenId id, String idempotencyKey);

    /** Read a token's current state. */
    Token find(TokenId id);
}
