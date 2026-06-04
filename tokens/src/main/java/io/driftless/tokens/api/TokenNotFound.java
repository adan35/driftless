package io.driftless.tokens.api;

import io.driftless.common.error.DomainException;
import io.driftless.common.id.TokenId;

/**
 * Thrown when a token operation references a {@link TokenId} that does not exist.
 *
 * <p>A typed {@link DomainException} so the web layer maps it to {@code 404 Not Found} and callers
 * (such as the auth saga reading a token's status) can distinguish an unknown token from a rejected
 * transition or an infrastructure failure.
 */
public class TokenNotFound extends DomainException {

    private final TokenId tokenId;

    public TokenNotFound(TokenId tokenId) {
        super("No token found with id %s".formatted(tokenId));
        this.tokenId = tokenId;
    }

    /** The token id that could not be found. */
    public TokenId tokenId() {
        return tokenId;
    }
}
