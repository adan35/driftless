package io.driftless.auth.web.dto;

import io.driftless.ledger.api.Account;
import java.util.UUID;

/**
 * Response for {@code POST /accounts} and {@code GET /accounts/{id}}: the account's id, type,
 * currency and name. Mapped from the ledger api {@link Account} so no internal type leaks.
 *
 * @param id the account id
 * @param type the double-entry account category
 * @param currency ISO-4217 currency code
 * @param name human-readable account name
 */
public record AccountResponse(UUID id, String type, String currency, String name) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.id().value(), account.type().name(), account.currency().getCurrencyCode(), account.name());
    }
}
