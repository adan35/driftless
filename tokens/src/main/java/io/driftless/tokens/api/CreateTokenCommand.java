package io.driftless.tokens.api;

import io.driftless.common.id.TokenId;
import java.util.Objects;
import java.util.Optional;

/**
 * Request to provision a new token for an underlying card.
 *
 * <p>The token is born {@link TokenStatus#INACTIVE}. The caller may supply a {@code tokenId} (e.g. a
 * client-generated id used as the natural idempotency anchor for the create); when absent, the
 * service mints a fresh one. {@code cardRef} is an opaque, log-safe reference to the card — never the
 * raw PAN (see {@link Token}).
 *
 * @param cardRef opaque reference/hash of the underlying card (never the raw PAN); must not be blank
 * @param tokenId optional client-supplied identifier; when present it makes {@code create} idempotent
 *     under retry, when absent the service mints a random {@link TokenId}
 */
public record CreateTokenCommand(String cardRef, Optional<TokenId> tokenId) {

    public CreateTokenCommand {
        Objects.requireNonNull(tokenId, "tokenId");
        if (cardRef == null || cardRef.isBlank()) {
            throw new IllegalArgumentException("cardRef must not be blank");
        }
    }

    /** Create a command for {@code cardRef}, letting the service mint the {@link TokenId}. */
    public static CreateTokenCommand forCard(String cardRef) {
        return new CreateTokenCommand(cardRef, Optional.empty());
    }

    /** Create a command for {@code cardRef} with a caller-supplied {@code tokenId} (idempotent create). */
    public static CreateTokenCommand forCard(String cardRef, TokenId tokenId) {
        return new CreateTokenCommand(cardRef, Optional.of(tokenId));
    }
}
