package io.driftless.auth.web.dto;

import io.driftless.auth.internal.AuthorizeCommand;
import io.driftless.common.id.AccountId;
import io.driftless.common.id.TokenId;
import io.driftless.common.money.Money;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Optional;

/**
 * Request body for {@code POST /authorizations}. Amount is integer minor units with an explicit
 * ISO-4217 currency (no float ever crosses the boundary); {@code tokenId} is optional and, when
 * present, gates the authorization on the token being {@code ACTIVE}.
 *
 * @param account the account id (UUID) the authorization is drawn against
 * @param amountMinor the requested amount in minor units (strictly positive)
 * @param currency ISO-4217 currency code
 * @param mcc merchant category code
 * @param merchantId opaque merchant identifier
 * @param tokenId optional gating token id (UUID)
 */
public record AuthorizeRequest(
        @NotBlank String account,
        @Min(1) long amountMinor,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @NotBlank String mcc,
        @NotBlank String merchantId,
        String tokenId) {

    /** Map the validated wire shape into the internal command (typed ids and {@code Money}). */
    public AuthorizeCommand toCommand() {
        Optional<TokenId> token =
                Optional.ofNullable(tokenId).filter(id -> !id.isBlank()).map(TokenId::of);
        return new AuthorizeCommand(AccountId.of(account), Money.of(amountMinor, currency), mcc, merchantId, token);
    }
}
