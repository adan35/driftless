package io.driftless.tokens.web.dto;

import io.driftless.common.id.TokenId;
import io.driftless.tokens.api.CreateTokenCommand;
import jakarta.validation.constraints.NotBlank;
import java.util.Optional;
import java.util.UUID;

/**
 * Request body for {@code POST /tokens}.
 *
 * @param cardRef opaque, log-safe reference to the underlying card (never the raw PAN); required
 * @param tokenId optional client-supplied token id; when present it anchors idempotent creation
 */
public record CreateTokenRequest(@NotBlank String cardRef, UUID tokenId) {

    /** Map to the domain command, wrapping an optional caller-supplied id. */
    public CreateTokenCommand toCommand() {
        return new CreateTokenCommand(cardRef, Optional.ofNullable(tokenId).map(TokenId::of));
    }
}
