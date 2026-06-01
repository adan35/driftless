package io.driftless.common.money;

import java.util.Currency;
import java.util.Objects;

/**
 * An immutable monetary amount expressed in integer <strong>minor units</strong> (cents, pence, …)
 * of an explicit ISO-4217 {@link Currency}.
 *
 * <p>No floating point ever touches a balance in Driftless: amounts are a {@code long} count of the
 * currency's smallest unit, and all arithmetic is integer arithmetic. Operations that combine two
 * amounts reject a currency mismatch with a typed {@link CurrencyMismatchException} rather than
 * performing any implicit conversion.
 *
 * @param amountMinor signed amount in the currency's minor unit; may be negative (e.g. a reversal)
 * @param currency non-null ISO-4217 currency
 */
public record Money(long amountMinor, Currency currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    /** Factory: an amount in minor units of the given currency. */
    public static Money of(long amountMinor, Currency currency) {
        return new Money(amountMinor, currency);
    }

    /** Factory: an amount in minor units of the currency named by its ISO-4217 code. */
    public static Money of(long amountMinor, String currencyCode) {
        return new Money(amountMinor, Currency.getInstance(currencyCode));
    }

    /** A zero amount in the given currency — the additive identity for that currency. */
    public static Money zero(Currency currency) {
        return new Money(0L, currency);
    }

    /**
     * Sum of this and {@code other}.
     *
     * @throws CurrencyMismatchException if the currencies differ
     * @throws ArithmeticException if the result overflows {@code long}
     */
    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amountMinor, other.amountMinor), currency);
    }

    /**
     * This minus {@code other}.
     *
     * @throws CurrencyMismatchException if the currencies differ
     * @throws ArithmeticException if the result overflows {@code long}
     */
    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(amountMinor, other.amountMinor), currency);
    }

    /**
     * This amount with its sign flipped — the additive inverse, used to build compensating entries.
     *
     * @throws ArithmeticException if this amount is {@link Long#MIN_VALUE} and cannot be negated
     */
    public Money negate() {
        return new Money(Math.negateExact(amountMinor), currency);
    }

    /** {@code true} when the amount is strictly greater than zero. */
    public boolean isPositive() {
        return amountMinor > 0L;
    }

    /** {@code true} when the amount is strictly less than zero. */
    public boolean isNegative() {
        return amountMinor < 0L;
    }

    /** {@code true} when the amount is exactly zero. */
    public boolean isZero() {
        return amountMinor == 0L;
    }

    /**
     * Compares magnitudes within a single currency, with the ordering contract of
     * {@link Comparable#compareTo}.
     *
     * @throws CurrencyMismatchException if the currencies differ
     */
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(amountMinor, other.amountMinor);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }
}
