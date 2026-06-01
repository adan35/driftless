package io.driftless.ledger.spi;

import io.driftless.common.id.AccountId;
import io.driftless.common.money.Money;
import java.util.Currency;

/**
 * SPI the ledger consults to compute an account's <em>available</em> balance.
 *
 * <p>Spec 03 (auth saga) owns the hold lifecycle (place/release/expire) and implements/feeds this
 * view; the ledger calls {@link #activeHoldTotal} so that {@code available = posted -
 * activeHoldTotal}. Defined here at the freeze so Spec 03 need not reopen the gate.
 */
public interface HoldView {

    /** Total of currently-active holds against the account in the given currency. */
    Money activeHoldTotal(AccountId account, Currency currency);
}
