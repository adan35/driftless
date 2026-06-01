package io.driftless.common.money;

import io.driftless.common.error.DomainException;
import java.util.Currency;

/**
 * Thrown when an operation combines two {@link Money} values that do not share a currency.
 *
 * <p>Cross-currency arithmetic is never silently coerced: there is no implicit FX in the kernel, so
 * adding USD to EUR is a programming error and is rejected loudly.
 */
public final class CurrencyMismatchException extends DomainException {

    public CurrencyMismatchException(Currency left, Currency right) {
        super("Currency mismatch: %s vs %s".formatted(left.getCurrencyCode(), right.getCurrencyCode()));
    }
}
