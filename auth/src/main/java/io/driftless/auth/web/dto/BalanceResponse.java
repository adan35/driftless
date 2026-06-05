package io.driftless.auth.web.dto;

import io.driftless.ledger.api.Balance;
import java.util.UUID;

/**
 * Response for {@code GET /accounts/{id}/balance}: posted and available in minor units. Available
 * reflects active holds ({@code available = posted - activeHoldTotal}).
 *
 * @param account the account id
 * @param currency ISO-4217 currency code
 * @param posted settled balance in minor units
 * @param available posted adjusted for active holds, in minor units
 */
public record BalanceResponse(UUID account, String currency, long posted, long available) {

    public static BalanceResponse from(Balance balance) {
        return new BalanceResponse(
                balance.account().value(),
                balance.currency().getCurrencyCode(),
                balance.posted().amountMinor(),
                balance.available().amountMinor());
    }
}
