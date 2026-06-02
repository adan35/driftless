package io.driftless.ledger;

import static io.driftless.ledger.LedgerFixtures.USD;
import static io.driftless.ledger.LedgerFixtures.account;
import static io.driftless.ledger.LedgerFixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;

import io.driftless.common.id.AccountId;
import io.driftless.common.money.Money;
import io.driftless.ledger.api.AccountType;
import io.driftless.ledger.api.Balance;
import io.driftless.ledger.api.Ledger;
import io.driftless.ledger.spi.HoldView;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Verifies that when a {@link HoldView} reports active holds, {@code available == posted -
 * activeHoldTotal}. Providing a {@link HoldView} bean here suppresses the module's
 * {@code @ConditionalOnMissingBean} no-op default — exactly how Spec 03 will override it.
 */
@SpringBootTest
@Import(PostgresTestContainer.class)
class LedgerHoldViewIT {

    /** A fixed hold of 3.00 USD on whichever account the test queries. */
    static final long HOLD_MINOR = 3_00L;

    @TestConfiguration(proxyBeanMethods = false)
    static class StubHoldViewConfig {
        @Bean
        HoldView stubHoldView() {
            return (AccountId account, Currency currency) -> Money.of(HOLD_MINOR, currency);
        }
    }

    @Autowired
    Ledger ledger;

    @Autowired
    HoldView holdView;

    @Test
    void availableIsPostedMinusActiveHoldTotal() {
        AccountId asset = AccountId.newId();
        AccountId liability = AccountId.newId();
        ledger.openAccount(account(asset, AccountType.ASSET, USD, "settlement"));
        ledger.openAccount(account(liability, AccountType.LIABILITY, USD, "cardholder"));
        ledger.post(transfer("hold-1", liability, asset, 10_00L, USD));

        // The stub bean suppresses the no-op default, so a non-zero hold is reported.
        assertThat(holdView.activeHoldTotal(asset, USD).amountMinor()).isEqualTo(HOLD_MINOR);

        Balance balance = ledger.balanceOf(asset);
        assertThat(balance.posted().amountMinor()).isEqualTo(10_00L);
        assertThat(balance.available().amountMinor()).isEqualTo(10_00L - HOLD_MINOR);
    }
}
