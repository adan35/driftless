package io.driftless.rules;

import static org.assertj.core.api.Assertions.assertThat;

import io.driftless.common.id.AccountId;
import io.driftless.common.money.Money;
import io.driftless.rules.api.AuthContext;
import io.driftless.rules.api.Decision;
import io.driftless.rules.api.RuleEngine;
import io.driftless.rules.api.RuleResult;
import io.driftless.rules.api.VelocitySnapshot;
import io.driftless.rules.config.RuleProperties;
import io.driftless.rules.internal.ReasonCodes;
import io.driftless.rules.velocity.VelocityStore;
import java.time.Instant;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Full Spring wiring test: rules authored in {@code application.yml} are bound into {@link
 * RuleProperties}, compiled at startup, and decide authorizations through the published {@link
 * RuleEngine} bean — with the {@link VelocityStore} bean available for the saga. This exercises the
 * real container path the {@code app} monolith uses, end to end.
 */
@SpringBootTest(classes = RulesTestApplication.class)
class RuleEngineWiringTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final AccountId ACCOUNT = AccountId.of("11111111-1111-1111-1111-111111111111");
    private static final Instant AT = Instant.parse("2026-06-01T12:00:00Z");

    @Autowired
    private RuleEngine ruleEngine;

    @Autowired
    private VelocityStore velocityStore;

    @Autowired
    private RuleProperties properties;

    @Test
    void authoredRulesAreBoundFromYaml() {
        assertThat(properties.getPerTransactionLimits()).hasSize(1);
        assertThat(properties.getPerTransactionLimits().get(0).getMaxAmountMinor())
                .isEqualTo(50_000);
        assertThat(properties.getMcc()).hasSize(1);
    }

    @Test
    void engineDecidesUsingTheYamlAuthoredPerTransactionLimit() {
        // $600 over the authored $500 per-txn cap.
        RuleResult result = ruleEngine.evaluate(context(usd(60_000), "5411", new VelocitySnapshot(0, usd(0))));
        assertThat(result.decision()).isEqualTo(Decision.DECLINE);
        assertThat(result.reasonCode()).isEqualTo(ReasonCodes.LIMIT_EXCEEDED);
    }

    @Test
    void engineDecidesUsingTheYamlAuthoredMccBlockList() {
        RuleResult result = ruleEngine.evaluate(context(usd(1_000), "7995", new VelocitySnapshot(0, usd(0))));
        assertThat(result.decision()).isEqualTo(Decision.DECLINE);
        assertThat(result.reasonCode()).isEqualTo(ReasonCodes.MCC_BLOCKED);
    }

    @Test
    void cleanContextApprovesThroughTheWiredEngine() {
        RuleResult result = ruleEngine.evaluate(context(usd(5_000), "5411", new VelocitySnapshot(0, usd(0))));
        assertThat(result.decision()).isEqualTo(Decision.APPROVE);
    }

    @Test
    void velocityStoreBeanRecordsIdempotentlyAndSnapshots() {
        AccountId account = AccountId.newId();
        assertThat(velocityStore.record("wire-auth-1", account, usd(2_000), AT)).isTrue();
        assertThat(velocityStore.record("wire-auth-1", account, usd(2_000), AT)).isFalse();

        VelocitySnapshot snapshot = velocityStore.snapshot(account, USD, AT);
        assertThat(snapshot.count()).isEqualTo(1);
        assertThat(snapshot.total()).isEqualTo(usd(2_000));
    }

    private static Money usd(long minor) {
        return Money.of(minor, USD);
    }

    private static AuthContext context(Money amount, String mcc, VelocitySnapshot velocity) {
        return new AuthContext(ACCOUNT, amount, mcc, "merchant-1", AT, usd(1_000_000), velocity);
    }
}
