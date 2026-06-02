package io.driftless.ledger.internal;

import io.driftless.common.id.AccountId;
import io.driftless.common.money.Money;
import io.driftless.ledger.spi.HoldView;
import java.util.Currency;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Publishes the ledger's default {@link HoldView}, which reports no active holds — so {@code
 * available == posted} until Spec 03 wires the real hold lifecycle.
 *
 * <p>Registered as an {@link AutoConfiguration} (not a component-scanned {@code @Configuration}) so
 * it is processed <em>after</em> application beans; combined with {@link ConditionalOnMissingBean},
 * any {@link HoldView} that Spec 03 (or a test) supplies cleanly replaces this default with no
 * duplicate-bean clash. Listed in {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 */
@AutoConfiguration
public class HoldViewConfig {

    @Bean
    @ConditionalOnMissingBean(HoldView.class)
    public HoldView noOpHoldView() {
        return (AccountId account, Currency currency) -> Money.zero(currency);
    }
}
