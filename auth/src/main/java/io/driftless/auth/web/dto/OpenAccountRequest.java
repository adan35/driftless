package io.driftless.auth.web.dto;

import io.driftless.auth.internal.OpenAccountCommand;
import io.driftless.ledger.api.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Currency;
import java.util.Locale;

/**
 * Request body for {@code POST /accounts}.
 *
 * <p>{@code type} is optional and defaults to {@code LIABILITY} (a cardholder funds account). Both
 * {@code type} and {@code currency} are validated into typed values in {@link #toCommand()}; an
 * unknown account type or a non ISO-4217 currency raises {@link IllegalArgumentException}, mapped to
 * {@code 400} with code {@code VALIDATION_FAILED}.
 *
 * @param type optional double-entry account category (e.g. {@code LIABILITY}); defaults to {@code
 *     LIABILITY}
 * @param currency ISO-4217 currency code (required)
 * @param name human-readable account name (required)
 */
public record OpenAccountRequest(
        String type, @NotBlank @Size(min = 3, max = 3) String currency, @NotBlank String name) {

    /** Map the validated wire shape into the typed internal command. */
    public OpenAccountCommand toCommand() {
        AccountType accountType = parseType(type);
        Currency parsedCurrency = parseCurrency(currency);
        return new OpenAccountCommand(accountType, parsedCurrency, name);
    }

    private static AccountType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return AccountType.LIABILITY;
        }
        try {
            return AccountType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown account type '%s'".formatted(raw));
        }
    }

    private static Currency parseCurrency(String code) {
        try {
            return Currency.getInstance(code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown ISO-4217 currency '%s'".formatted(code));
        }
    }
}
