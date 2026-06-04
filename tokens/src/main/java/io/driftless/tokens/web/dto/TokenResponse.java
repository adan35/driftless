package io.driftless.tokens.web.dto;

import io.driftless.tokens.api.Token;
import io.driftless.tokens.api.TokenStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Wire representation of a {@link Token} — the DTO returned by every token endpoint so JPA entities
 * never leak across the web boundary.
 *
 * @param id the token id
 * @param cardRef the log-safe card reference (never the raw PAN)
 * @param status the current lifecycle state — what the auth saga gates authorizations on
 * @param updatedAt business time of the last status change
 */
public record TokenResponse(UUID id, String cardRef, TokenStatus status, Instant updatedAt) {

    /** Project a domain {@link Token} onto its wire shape. */
    public static TokenResponse from(Token token) {
        return new TokenResponse(token.id().value(), token.cardRef(), token.status(), token.updatedAt());
    }
}
