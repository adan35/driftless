package io.driftless.rules.internal;

import static io.driftless.rules.RuleFixtures.GAMBLING_MCC;
import static io.driftless.rules.RuleFixtures.GROCERIES_MCC;
import static io.driftless.rules.RuleFixtures.cleanContext;
import static io.driftless.rules.RuleFixtures.context;
import static io.driftless.rules.RuleFixtures.mccAllow;
import static io.driftless.rules.RuleFixtures.mccBlock;
import static io.driftless.rules.RuleFixtures.perTxnLimit;
import static io.driftless.rules.RuleFixtures.periodicCap;
import static io.driftless.rules.RuleFixtures.usd;
import static io.driftless.rules.RuleFixtures.velocityAmount;
import static io.driftless.rules.RuleFixtures.velocityCount;
import static org.assertj.core.api.Assertions.assertThat;

import io.driftless.rules.api.AuthContext;
import io.driftless.rules.api.Decision;
import io.driftless.rules.api.RuleResult;
import io.driftless.rules.api.VelocitySnapshot;
import io.driftless.rules.config.RuleProperties;
import org.junit.jupiter.api.Test;

/**
 * Acceptance-criteria tests for {@link RuleEvaluator}: each MVP rule type's decline path, the clean
 * approve, determinism, and deterministic priority ordering. The engine is wired in plain Java with no
 * Spring context and no datasource — proving the hot path is I/O-free by construction.
 */
class RuleEvaluatorTest {

    @Test
    void cleanTransactionApproves() {
        RuleProperties properties = new RuleProperties();
        properties.getPerTransactionLimits().add(perTxnLimit("per-txn", 10, 10_000));
        properties.getMcc().add(mccBlock("mcc-block", 20, GAMBLING_MCC));
        EngineHarness harness = new EngineHarness(properties);

        RuleResult result = harness.evaluator.evaluate(cleanContext());

        assertThat(result.decision()).isEqualTo(Decision.APPROVE);
        assertThat(result.isApproved()).isTrue();
        assertThat(result.ruleId()).isNull();
        assertThat(result.reasonCode()).isNull();
    }

    @Test
    void overPerTransactionLimitDeclinesLimitExceeded() {
        RuleProperties properties = new RuleProperties();
        properties.getPerTransactionLimits().add(perTxnLimit("per-txn-50", 10, 5_000));
        EngineHarness harness = new EngineHarness(properties);

        // $50.01 against a $50.00 cap.
        AuthContext context = context(usd(5_001), GROCERIES_MCC, usd(100_000), new VelocitySnapshot(0, usd(0)));
        RuleResult result = harness.evaluator.evaluate(context);

        assertThat(result.decision()).isEqualTo(Decision.DECLINE);
        assertThat(result.ruleId()).isEqualTo("per-txn-50");
        assertThat(result.reasonCode()).isEqualTo(ReasonCodes.LIMIT_EXCEEDED);
    }

    @Test
    void exactlyAtPerTransactionLimitApproves() {
        RuleProperties properties = new RuleProperties();
        properties.getPerTransactionLimits().add(perTxnLimit("per-txn-50", 10, 5_000));
        EngineHarness harness = new EngineHarness(properties);

        // Exactly $50.00 — the cap is a maximum, not a strict-less-than.
        AuthContext context = context(usd(5_000), GROCERIES_MCC, usd(100_000), new VelocitySnapshot(0, usd(0)));
        assertThat(harness.evaluator.evaluate(context).decision()).isEqualTo(Decision.APPROVE);
    }

    @Test
    void exceedingVelocityCountDeclinesVelocity() {
        RuleProperties properties = new RuleProperties();
        properties.getVelocity().add(velocityCount("vel-count-3", 10, 3));
        EngineHarness harness = new EngineHarness(properties);

        // Window already holds 3 against a max of 3 — the 4th is declined.
        AuthContext context = context(usd(1_000), GROCERIES_MCC, usd(100_000), new VelocitySnapshot(3, usd(3_000)));
        RuleResult result = harness.evaluator.evaluate(context);

        assertThat(result.decision()).isEqualTo(Decision.DECLINE);
        assertThat(result.ruleId()).isEqualTo("vel-count-3");
        assertThat(result.reasonCode()).isEqualTo(ReasonCodes.VELOCITY);
    }

    @Test
    void underVelocityCountApproves() {
        RuleProperties properties = new RuleProperties();
        properties.getVelocity().add(velocityCount("vel-count-3", 10, 3));
        EngineHarness harness = new EngineHarness(properties);

        AuthContext context = context(usd(1_000), GROCERIES_MCC, usd(100_000), new VelocitySnapshot(2, usd(2_000)));
        assertThat(harness.evaluator.evaluate(context).decision()).isEqualTo(Decision.APPROVE);
    }

    @Test
    void exceedingVelocityAmountDeclinesVelocity() {
        RuleProperties properties = new RuleProperties();
        properties.getVelocity().add(velocityAmount("vel-sum-200", 10, 20_000));
        EngineHarness harness = new EngineHarness(properties);

        // $150 already in window + $60 now = $210 against a $200 sum cap.
        AuthContext context = context(usd(6_000), GROCERIES_MCC, usd(100_000), new VelocitySnapshot(2, usd(15_000)));
        RuleResult result = harness.evaluator.evaluate(context);

        assertThat(result.decision()).isEqualTo(Decision.DECLINE);
        assertThat(result.reasonCode()).isEqualTo(ReasonCodes.VELOCITY);
    }

    @Test
    void blockedMccDeclinesMccBlocked() {
        RuleProperties properties = new RuleProperties();
        properties.getMcc().add(mccBlock("mcc-no-gambling", 10, GAMBLING_MCC));
        EngineHarness harness = new EngineHarness(properties);

        AuthContext context = context(usd(1_000), GAMBLING_MCC, usd(100_000), new VelocitySnapshot(0, usd(0)));
        RuleResult result = harness.evaluator.evaluate(context);

        assertThat(result.decision()).isEqualTo(Decision.DECLINE);
        assertThat(result.ruleId()).isEqualTo("mcc-no-gambling");
        assertThat(result.reasonCode()).isEqualTo(ReasonCodes.MCC_BLOCKED);
    }

    @Test
    void allowListDeclinesUnlistedMcc() {
        RuleProperties properties = new RuleProperties();
        properties.getMcc().add(mccAllow("mcc-only-groceries", 10, GROCERIES_MCC));
        EngineHarness harness = new EngineHarness(properties);

        AuthContext blocked = context(usd(1_000), GAMBLING_MCC, usd(100_000), new VelocitySnapshot(0, usd(0)));
        assertThat(harness.evaluator.evaluate(blocked).reasonCode()).isEqualTo(ReasonCodes.MCC_BLOCKED);

        AuthContext allowed = context(usd(1_000), GROCERIES_MCC, usd(100_000), new VelocitySnapshot(0, usd(0)));
        assertThat(harness.evaluator.evaluate(allowed).decision()).isEqualTo(Decision.APPROVE);
    }

    @Test
    void periodicCapDeclinesWhenProjectedSpendExceedsCap() {
        RuleProperties properties = new RuleProperties();
        properties.getPeriodicCaps().add(periodicCap("daily-cap-1000", 10, 100_000));
        EngineHarness harness = new EngineHarness(properties);

        // $980 spent today + $50 now = $1030 against a $1000 daily cap.
        AuthContext context = context(usd(5_000), GROCERIES_MCC, usd(100_000), new VelocitySnapshot(9, usd(98_000)));
        RuleResult result = harness.evaluator.evaluate(context);

        assertThat(result.decision()).isEqualTo(Decision.DECLINE);
        assertThat(result.reasonCode()).isEqualTo(ReasonCodes.LIMIT_EXCEEDED);
    }

    @Test
    void evaluationIsDeterministicForTheSameContext() {
        RuleProperties properties = new RuleProperties();
        properties.getPerTransactionLimits().add(perTxnLimit("per-txn", 10, 5_000));
        properties.getVelocity().add(velocityCount("vel", 20, 5));
        properties.getMcc().add(mccBlock("mcc", 30, GAMBLING_MCC));
        EngineHarness harness = new EngineHarness(properties);

        AuthContext context = context(usd(9_999), GROCERIES_MCC, usd(100_000), new VelocitySnapshot(1, usd(1_000)));
        RuleResult first = harness.evaluator.evaluate(context);
        for (int i = 0; i < 1_000; i++) {
            assertThat(harness.evaluator.evaluate(context)).isEqualTo(first);
        }
    }

    @Test
    void lowestPriorityRuleWinsWhenSeveralWouldDecline() {
        RuleProperties properties = new RuleProperties();
        // Both fire for this context; MCC has the lower priority value so it must decide.
        properties.getPerTransactionLimits().add(perTxnLimit("per-txn", 50, 5_000));
        properties.getMcc().add(mccBlock("mcc", 10, GAMBLING_MCC));
        EngineHarness harness = new EngineHarness(properties);

        AuthContext context = context(usd(9_999), GAMBLING_MCC, usd(100_000), new VelocitySnapshot(0, usd(0)));
        RuleResult result = harness.evaluator.evaluate(context);

        assertThat(result.ruleId()).isEqualTo("mcc");
        assertThat(result.reasonCode()).isEqualTo(ReasonCodes.MCC_BLOCKED);
    }

    @Test
    void emptyRuleSetApprovesEverything() {
        EngineHarness harness = new EngineHarness(new RuleProperties());
        assertThat(harness.activeRuleCount()).isZero();
        assertThat(harness.evaluator.evaluate(cleanContext()).decision()).isEqualTo(Decision.APPROVE);
    }
}
