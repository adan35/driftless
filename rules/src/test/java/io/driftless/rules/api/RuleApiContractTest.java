package io.driftless.rules.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.driftless.common.id.AccountId;
import io.driftless.common.money.Money;
import java.time.Instant;
import java.util.Currency;
import org.junit.jupiter.api.Test;

/** Guards on the public api records: the validation each compact constructor and factory enforces. */
class RuleApiContractTest {

    private static final Currency USD = Currency.getInstance("USD");

    @Test
    void velocitySnapshotRejectsNegativeCountAndTotal() {
        assertThatThrownBy(() -> new VelocitySnapshot(-1, Money.zero(USD)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("count");
        assertThatThrownBy(() -> new VelocitySnapshot(0, Money.of(-1, USD)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("total");
    }

    @Test
    void velocitySnapshotEmptyHasZeroCountAndTotal() {
        VelocitySnapshot empty = VelocitySnapshot.empty(Money.zero(USD));
        assertThat(empty.count()).isZero();
        assertThat(empty.total()).isEqualTo(Money.zero(USD));
    }

    @Test
    void ruleResultApproveCarriesNoRuleOrReason() {
        RuleResult approve = RuleResult.approve();
        assertThat(approve.decision()).isEqualTo(Decision.APPROVE);
        assertThat(approve.isApproved()).isTrue();
        assertThat(approve.ruleId()).isNull();
        assertThat(approve.reasonCode()).isNull();
        assertThat(approve.decidingRuleId()).isEmpty();
        assertThat(approve.reason()).isEmpty();
    }

    @Test
    void ruleResultDeclineRequiresAReasonCode() {
        assertThatThrownBy(() -> RuleResult.decline("rule", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reasonCode");
        assertThatThrownBy(() -> new RuleResult(Decision.DECLINE, "rule", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ruleResultDeclineExposesRuleAndReason() {
        RuleResult decline = RuleResult.decline("per-txn", "LIMIT_EXCEEDED");
        assertThat(decline.isApproved()).isFalse();
        assertThat(decline.decidingRuleId()).contains("per-txn");
        assertThat(decline.reason()).contains("LIMIT_EXCEEDED");
    }

    @Test
    void authContextRejectsNullComponents() {
        assertThatThrownBy(() -> new AuthContext(
                        null, Money.of(1, USD), "5411", "m", Instant.EPOCH, Money.of(1, USD), emptyVelocity()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AuthContext(
                        AccountId.newId(), Money.of(1, USD), "5411", "m", Instant.EPOCH, Money.of(1, USD), null))
                .isInstanceOf(NullPointerException.class);
    }

    private static VelocitySnapshot emptyVelocity() {
        return VelocitySnapshot.empty(Money.zero(USD));
    }
}
