package io.driftless.ledger.api;

import io.driftless.common.id.AccountId;
import io.driftless.common.money.Money;
import java.util.Currency;

/**
 * Posted vs available is surfaced here; available accounts for holds (see Spec 03).
 *
 * @param posted sum of settled journal entries
 * @param available posted adjusted for active holds; {@code == posted} when no holds
 */
public record Balance(AccountId account, Currency currency, Money posted, Money available) {}
