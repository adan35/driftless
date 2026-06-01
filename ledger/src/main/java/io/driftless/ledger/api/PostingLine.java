package io.driftless.ledger.api;

import io.driftless.common.id.AccountId;
import io.driftless.common.money.Money;

/**
 * One leg of a balanced transaction. {@code amount} is always positive minor units; {@link
 * Direction} carries the sign. {@code Money.currency} MUST match the account's currency.
 */
public record PostingLine(AccountId account, Direction direction, Money amount) {}
