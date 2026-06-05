package io.driftless.auth.web.dto;

import io.driftless.common.money.Money;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Currency;

/**
 * Request body for {@code POST /accounts/{id}/funding}: the amount to load, in integer minor units,
 * with an explicit ISO-4217 currency (no float ever crosses the boundary). The currency must match
 * the target account's currency.
 *
 * @param amountMinor amount to load in minor units (strictly positive)
 * @param currency ISO-4217 currency code
 */
public record FundAccountRequest(@Min(1) long amountMinor, @NotBlank @Size(min = 3, max = 3) String currency) {

    /** Map to a typed {@link Money}; an unknown currency raises {@link IllegalArgumentException}. */
    public Money toMoney() {
        try {
            return Money.of(amountMinor, Currency.getInstance(currency.trim().toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown ISO-4217 currency '%s'".formatted(currency));
        }
    }
}
