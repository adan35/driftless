package io.driftless.ledger.api;

import io.driftless.common.id.AccountId;
import io.driftless.common.money.Money;
import java.util.Objects;

/**
 * One leg of a balanced transaction. {@code amount} is always positive minor units; {@link
 * Direction} carries the sign. {@code Money.currency} MUST match the account's currency.
 *
 * <p>The compact constructor enforces the strictly-positive-amount invariant that this contract has
 * always documented: {@link Money} itself is deliberately signed (it also models balances, deltas
 * and reversals), but a posting line's magnitude is positive and {@link Direction} carries the sign.
 * This is a shape-preserving guard — the record's components and accessors are unchanged.
 */
public record PostingLine(AccountId account, Direction direction, Money amount) {

    public PostingLine {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(amount, "amount");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException(
                    "PostingLine.amount must be strictly positive minor units; Direction carries the sign (was "
                            + amount.amountMinor() + ")");
        }
    }
}
