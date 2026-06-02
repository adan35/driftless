package io.driftless.ledger.internal.error;

import io.driftless.common.error.DomainException;
import io.driftless.common.id.AccountId;
import java.util.Currency;

/**
 * Thrown by {@code Ledger#post} when a {@code PostingLine}'s {@code Money.currency} differs from the
 * currency of the account it posts to.
 *
 * <p>A leg must be denominated in its account's currency; there is no implicit conversion. The
 * request is rejected and nothing is written. Catchable as {@link DomainException}.
 */
public final class AccountCurrencyMismatch extends DomainException {

    public AccountCurrencyMismatch(AccountId account, Currency lineCurrency, Currency accountCurrency) {
        super("Line currency %s does not match account %s currency %s"
                .formatted(lineCurrency.getCurrencyCode(), account, accountCurrency.getCurrencyCode()));
    }
}
