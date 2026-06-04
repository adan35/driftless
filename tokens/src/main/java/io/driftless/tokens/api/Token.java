package io.driftless.tokens.api;

import io.driftless.common.id.TokenId;
import java.time.Instant;
import java.util.Objects;

/**
 * A payment token: the issuer-side mapping of an underlying card to a wallet/device token, with its
 * own lifecycle ({@link TokenStatus}) independent of the card.
 *
 * <p><strong>PAN safety.</strong> {@code cardRef} is a non-sensitive <em>reference</em> to the
 * underlying card (an opaque handle or hash), never the raw PAN. Real PAN encryption/HSM/PCI handling
 * is out of scope for Spec 06 (future work); this module stores and logs only the reference, so the
 * raw card number never reaches a persisted column or a log line.
 *
 * @param id the strongly-typed token identifier
 * @param cardRef opaque, log-safe reference to the underlying card (never the raw PAN)
 * @param status the token's current lifecycle state
 * @param updatedAt business time of the last status change, sourced from the injected {@code Clock}
 */
public record Token(TokenId id, String cardRef, TokenStatus status, Instant updatedAt) {

    public Token {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (cardRef == null || cardRef.isBlank()) {
            throw new IllegalArgumentException("cardRef must not be blank");
        }
    }
}
