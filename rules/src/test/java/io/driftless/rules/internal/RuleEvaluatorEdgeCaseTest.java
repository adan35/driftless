package io.driftless.rules.internal;

import static io.driftless.rules.RuleFixtures.GROCERIES_MCC;
import static io.driftless.rules.RuleFixtures.context;
import static io.driftless.rules.RuleFixtures.mccBlock;
import static io.driftless.rules.RuleFixtures.periodicCap;
import static io.driftless.rules.RuleFixtures.usd;
import static io.driftless.rules.RuleFixtures.velocityAmount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.driftless.rules.api.AuthContext;
import io.driftless.rules.api.Decision;
import io.driftless.rules.api.RuleResult;
import io.driftless.rules.api.VelocitySnapshot;
import io.driftless.rules.config.RuleProperties;
import org.junit.jupiter.api.Test;

/**
 * QA edge-case probes (Spec 05) beyond the happy-path acceptance tests: boundary amounts, empty MCC,
 * and a robustness probe for {@code long} overflow on the windowed-sum projection.
 */
class RuleEvaluatorEdgeCaseTest {

    /**
     * ROBUSTNESS PROBE -- overflow on the hot path. {@code PeriodicSpendCap} / {@code VelocityAmount}
     * project {@code velocityTotal + amount} with {@link Math#addExact}, which THROWS on {@code long}
     * overflow instead of declining. A velocity snapshot near {@link Long#MAX_VALUE} (allowed by the
     * {@code VelocitySnapshot} contract, which only forbids negatives) therefore makes {@code evaluate}
     * throw an unchecked {@link ArithmeticException} on the authorization hot path rather than returning
     * a DECLINE. The saga has no datasource to stall here, but an uncaught exception is a hard failure
     * of an otherwise total function. This test pins the CURRENT behaviour so the team can decide
     * whether to saturate instead.
     */
    @Test
    void windowedSumProjectionOverflowsInsteadOfDecliningAtExtremeVelocityTotals() {
        RuleProperties properties = new RuleProperties();
        properties.getVelocity().add(velocityAmount("vel-sum", 10, 1_000_000));
        EngineHarness harness = new EngineHarness(properties);

        // A near-max windowed total plus a positive amount overflows long.
        AuthContext context =
                context(usd(1_000), GROCERIES_MCC, usd(100_000), new VelocitySnapshot(1, usd(Long.MAX_VALUE - 10)));

        assertThatThrownBy(() -> harness.evaluator.evaluate(context))
                .as("windowed-sum projection currently overflows rather than saturating to a DECLINE")
                .isInstanceOf(ArithmeticException.class);
    }

    /** The same overflow risk on the periodic spend cap projection. */
    @Test
    void periodicCapProjectionOverflowsAtExtremeWindowSpend() {
        RuleProperties properties = new RuleProperties();
        properties.getPeriodicCaps().add(periodicCap("daily", 10, 1_000_000));
        EngineHarness harness = new EngineHarness(properties);

        AuthContext context =
                context(usd(50), GROCERIES_MCC, usd(100_000), new VelocitySnapshot(1, usd(Long.MAX_VALUE)));

        assertThatThrownBy(() -> harness.evaluator.evaluate(context)).isInstanceOf(ArithmeticException.class);
    }

    /** A zero-amount authorization is within any positive per-txn limit and any cap -- it approves. */
    @Test
    void zeroAmountAuthorizationApprovesUnderPositiveLimits() {
        RuleProperties properties = new RuleProperties();
        properties.getPerTransactionLimits().add(io.driftless.rules.RuleFixtures.perTxnLimit("per-txn", 10, 5_000));
        properties.getPeriodicCaps().add(periodicCap("daily", 20, 100_000));
        EngineHarness harness = new EngineHarness(properties);

        AuthContext zero = context(usd(0), GROCERIES_MCC, usd(100_000), new VelocitySnapshot(0, usd(0)));
        assertThat(harness.evaluator.evaluate(zero).decision()).isEqualTo(Decision.APPROVE);
    }

    /**
     * An empty/blank MCC against a block list is not in the blocked set, so the MCC rule does not fire.
     * (Documents that the engine does not treat a missing MCC as blocked by default.)
     */
    @Test
    void emptyMccIsNotBlockedByABlockList() {
        RuleProperties properties = new RuleProperties();
        properties.getMcc().add(mccBlock("mcc-block", 10, "7995", "6011"));
        EngineHarness harness = new EngineHarness(properties);

        AuthContext emptyMcc = context(usd(1_000), "", usd(100_000), new VelocitySnapshot(0, usd(0)));
        assertThat(harness.evaluator.evaluate(emptyMcc).decision()).isEqualTo(Decision.APPROVE);
    }

    /**
     * Exactly-at the velocity-amount cap approves (the cap is a maximum, strict-greater-than declines):
     * windowed $9.99 + $0.01 = $10.00 against a $10.00 cap.
     */
    @Test
    void exactlyAtWindowedSumCapApproves() {
        RuleProperties properties = new RuleProperties();
        properties.getVelocity().add(velocityAmount("vel-sum-1000", 10, 1_000));
        EngineHarness harness = new EngineHarness(properties);

        AuthContext atCap = context(usd(1), GROCERIES_MCC, usd(100_000), new VelocitySnapshot(1, usd(999)));
        assertThat(harness.evaluator.evaluate(atCap).decision()).isEqualTo(Decision.APPROVE);
    }

    /**
     * Determinism across rule types and stable tie-break: when two rules at equal priority both fire,
     * the lower ruleId decides, and that decision is stable across repeated evaluations.
     */
    @Test
    void equalPriorityTieBreaksOnRuleIdDeterministically() {
        RuleProperties properties = new RuleProperties();
        // Two per-txn limits at the SAME priority; both fire for a large amount. ruleId breaks the tie.
        properties.getPerTransactionLimits().add(io.driftless.rules.RuleFixtures.perTxnLimit("aaa-limit", 10, 1_000));
        properties.getPerTransactionLimits().add(io.driftless.rules.RuleFixtures.perTxnLimit("zzz-limit", 10, 1_000));
        EngineHarness harness = new EngineHarness(properties);

        AuthContext over = context(usd(9_999), GROCERIES_MCC, usd(100_000), new VelocitySnapshot(0, usd(0)));
        RuleResult first = harness.evaluator.evaluate(over);
        assertThat(first.decision()).isEqualTo(Decision.DECLINE);
        assertThat(first.ruleId()).isEqualTo("aaa-limit");
        for (int i = 0; i < 100; i++) {
            assertThat(harness.evaluator.evaluate(over)).isEqualTo(first);
        }
    }
}
