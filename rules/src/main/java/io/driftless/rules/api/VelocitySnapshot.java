package io.driftless.rules.api;

import io.driftless.common.money.Money;
import java.util.Objects;

/**
 * An immutable, point-in-time summary of an account's recent authorization activity within a rolling
 * window, supplied to {@link RuleEngine#evaluate} so the engine can decide velocity and periodic-cap
 * rules <strong>without</strong> a database aggregation on the hot path.
 *
 * <p>The saga (Spec 03) reads this from the in-memory velocity store and passes it in via {@link
 * AuthContext}; the engine never computes it from a {@code GROUP BY}.
 *
 * @param count number of authorizations counted in the active window (always {@code >= 0})
 * @param total summed amount of those authorizations in the active window (minor units, never
 *     negative); its currency is the account's settlement currency
 */
public record VelocitySnapshot(int count, Money total) {

    public VelocitySnapshot {
        Objects.requireNonNull(total, "total");
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative (was " + count + ")");
        }
        if (total.isNegative()) {
            throw new IllegalArgumentException("total must not be negative (was " + total + ")");
        }
    }

    /** An empty window — no authorizations counted — in the given currency. */
    public static VelocitySnapshot empty(Money zeroOfCurrency) {
        return new VelocitySnapshot(0, zeroOfCurrency);
    }
}
