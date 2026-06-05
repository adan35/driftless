package io.driftless.auth.internal;

import io.driftless.ledger.api.AccountType;
import java.util.Currency;
import java.util.Objects;

/**
 * Internal command to open a cardholder/target ledger account through the public REST surface.
 *
 * <p>The validated wire shape ({@code OpenAccountRequest}) is mapped to this command — typed {@link
 * AccountType} and {@link Currency} — so the controller stays thin and the service never parses
 * strings.
 *
 * @param type the double-entry account category (defaults to {@code LIABILITY} for a cardholder)
 * @param currency ISO-4217 currency the account is denominated in
 * @param name human-readable account name
 */
public record OpenAccountCommand(AccountType type, Currency currency, String name) {

    public OpenAccountCommand {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(currency, "currency");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("account name must not be blank");
        }
    }
}
