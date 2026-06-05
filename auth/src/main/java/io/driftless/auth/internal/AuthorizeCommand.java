package io.driftless.auth.internal;

import io.driftless.common.id.AccountId;
import io.driftless.common.id.TokenId;
import io.driftless.common.money.Money;
import java.util.Objects;
import java.util.Optional;

/**
 * The fully-assembled input to {@link AuthorizationSaga#authorize}, built by the web layer from the
 * request body. {@code amount} carries its own currency (minor units), which becomes the
 * authorization's settlement currency and must match the account.
 *
 * @param account the account the authorization is drawn against
 * @param amount the requested amount (positive minor units, with currency)
 * @param mcc merchant category code
 * @param merchantId opaque merchant identifier
 * @param tokenId optional gating token; when present, authorization requires it to be {@code ACTIVE}
 */
public record AuthorizeCommand(
        AccountId account, Money amount, String mcc, String merchantId, Optional<TokenId> tokenId) {

    public AuthorizeCommand {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(mcc, "mcc");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(tokenId, "tokenId");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("authorization amount must be strictly positive minor units");
        }
    }
}
