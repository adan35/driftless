package io.driftless.auth.internal;

import io.driftless.auth.internal.persistence.HoldRepository;
import io.driftless.common.id.AccountId;
import io.driftless.common.money.Money;
import io.driftless.ledger.spi.HoldView;
import java.util.Currency;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The Spec 03 implementation of the ledger {@link HoldView} SPI — the real bean that replaces the
 * ledger's {@code @ConditionalOnMissingBean} no-op.
 *
 * <p>It reports the total of currently-ACTIVE holds for an account/currency, summed from the {@code
 * hold} table this module owns. The ledger calls it inside {@code balanceOf} so {@code available =
 * posted - activeHoldTotal}: a placed hold drops available immediately while posted is unchanged, and
 * releasing the hold restores it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HoldViewImpl implements HoldView {

    private final HoldRepository holds;

    @Override
    public Money activeHoldTotal(AccountId account, Currency currency) {
        long totalMinor = holds.activeHoldTotalMinor(account.value(), currency.getCurrencyCode());
        log.debug("activeHoldTotal account={} currency={} total={}", account, currency.getCurrencyCode(), totalMinor);
        return Money.of(totalMinor, currency);
    }
}
