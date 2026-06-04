package io.driftless.tokens.api;

import io.driftless.common.error.DomainException;
import io.driftless.common.id.TokenId;

/**
 * Thrown when a token operation is attempted from a state that does not permit it — for example
 * {@code resume} on an {@code ACTIVE} token, or any transition out of the terminal {@code
 * DEACTIVATED} state.
 *
 * <p>Raising this changes <em>nothing</em>: the token keeps its current status, no {@code
 * token_status_history} row is written, and no {@code TokenStatusChanged} event is emitted. It is a
 * typed {@link DomainException} so the web layer can map it (Spec 03) and callers can distinguish a
 * rejected transition from infrastructure failure.
 */
public class IllegalTokenTransition extends DomainException {

    private final TokenId tokenId;
    private final TokenStatus from;
    private final TokenTransition attempted;

    public IllegalTokenTransition(TokenId tokenId, TokenStatus from, TokenTransition attempted) {
        super("Cannot %s token %s in state %s".formatted(attempted, tokenId, from));
        this.tokenId = tokenId;
        this.from = from;
        this.attempted = attempted;
    }

    /** The token whose transition was rejected. */
    public TokenId tokenId() {
        return tokenId;
    }

    /** The state the token was in when the transition was attempted. */
    public TokenStatus from() {
        return from;
    }

    /** The transition that was rejected. */
    public TokenTransition attempted() {
        return attempted;
    }
}
