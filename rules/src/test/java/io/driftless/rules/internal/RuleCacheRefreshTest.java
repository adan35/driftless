package io.driftless.rules.internal;

import static io.driftless.rules.RuleFixtures.GROCERIES_MCC;
import static io.driftless.rules.RuleFixtures.context;
import static io.driftless.rules.RuleFixtures.mccBlock;
import static io.driftless.rules.RuleFixtures.perTxnLimit;
import static io.driftless.rules.RuleFixtures.usd;
import static org.assertj.core.api.Assertions.assertThat;

import io.driftless.rules.api.AuthContext;
import io.driftless.rules.api.Decision;
import io.driftless.rules.api.VelocitySnapshot;
import io.driftless.rules.config.RuleProperties;
import org.junit.jupiter.api.Test;

/**
 * Proves a rule change takes effect after a cache refresh with no restart, and that a refresh which
 * fails to compile keeps the previously-good rule set rather than emptying the engine.
 */
class RuleCacheRefreshTest {

    private static AuthContext fiftyDollars() {
        return context(usd(5_000), GROCERIES_MCC, usd(100_000), new VelocitySnapshot(0, usd(0)));
    }

    @Test
    void tighteningALimitTakesEffectAfterRefreshWithoutRestart() {
        RuleProperties properties = new RuleProperties();
        properties.getPerTransactionLimits().add(perTxnLimit("per-txn", 10, 10_000)); // $100 cap
        EngineHarness harness = new EngineHarness(properties);

        // $50 approves under the $100 cap.
        assertThat(harness.evaluator.evaluate(fiftyDollars()).decision()).isEqualTo(Decision.APPROVE);

        // Author a tighter $40 cap on the SAME running engine, then refresh.
        properties.getPerTransactionLimits().clear();
        properties.getPerTransactionLimits().add(perTxnLimit("per-txn", 10, 4_000)); // $40 cap
        assertThat(harness.refresh()).isTrue();

        // The very next evaluation — no restart — now declines $50.
        assertThat(harness.evaluator.evaluate(fiftyDollars()).decision()).isEqualTo(Decision.DECLINE);
        assertThat(harness.evaluator.evaluate(fiftyDollars()).reasonCode()).isEqualTo(ReasonCodes.LIMIT_EXCEEDED);
    }

    @Test
    void addingARuleAtRuntimeTakesEffectAfterRefresh() {
        RuleProperties properties = new RuleProperties();
        EngineHarness harness = new EngineHarness(properties);
        assertThat(harness.activeRuleCount()).isZero();
        assertThat(harness.evaluator.evaluate(fiftyDollars()).decision()).isEqualTo(Decision.APPROVE);

        properties.getMcc().add(mccBlock("mcc", 10, GROCERIES_MCC));
        assertThat(harness.refresh()).isTrue();

        assertThat(harness.activeRuleCount()).isEqualTo(1);
        assertThat(harness.evaluator.evaluate(fiftyDollars()).reasonCode()).isEqualTo(ReasonCodes.MCC_BLOCKED);
    }

    @Test
    void aFailedCompileKeepsThePreviousGoodRuleSet() {
        RuleProperties properties = new RuleProperties();
        properties.getPerTransactionLimits().add(perTxnLimit("per-txn", 10, 4_000)); // $40 cap
        EngineHarness harness = new EngineHarness(properties);
        assertThat(harness.evaluator.evaluate(fiftyDollars()).decision()).isEqualTo(Decision.DECLINE);

        // Introduce a misconfiguration: a duplicate ruleId. Compilation must throw and be swallowed.
        properties.getPerTransactionLimits().add(perTxnLimit("per-txn", 20, 999_999));
        assertThat(harness.refresh()).isFalse();

        // The previously-good $40 cap still governs — the engine did not go empty/approve-all.
        assertThat(harness.activeRuleCount()).isEqualTo(1);
        assertThat(harness.evaluator.evaluate(fiftyDollars()).decision()).isEqualTo(Decision.DECLINE);
    }
}
