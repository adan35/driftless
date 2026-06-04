package io.driftless.rules.internal;

import static io.driftless.rules.RuleFixtures.perTxnLimit;
import static io.driftless.rules.RuleFixtures.usd;
import static io.driftless.rules.RuleFixtures.velocityCount;
import static org.assertj.core.api.Assertions.assertThat;

import io.driftless.common.id.AccountId;
import io.driftless.rules.api.AuthContext;
import io.driftless.rules.api.Decision;
import io.driftless.rules.api.RuleResult;
import io.driftless.rules.api.VelocitySnapshot;
import io.driftless.rules.config.RuleProperties;
import java.time.Instant;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property: over a wide space of randomly-generated authorization contexts, {@link RuleEvaluator} is
 * deterministic — evaluating the same context twice yields {@code equals} results — and its decision
 * matches an independent reference oracle for the configured rules. This guards the determinism
 * acceptance criterion across far more inputs than the example tests.
 */
class RuleEngineDeterminismPropertyTest {

    private static final long PER_TXN_CAP = 50_000; // $500.00
    private static final int VELOCITY_MAX = 5;

    private final EngineHarness harness = buildHarness();

    private static EngineHarness buildHarness() {
        RuleProperties properties = new RuleProperties();
        properties.getPerTransactionLimits().add(perTxnLimit("per-txn", 10, PER_TXN_CAP));
        properties.getVelocity().add(velocityCount("vel", 20, VELOCITY_MAX));
        return new EngineHarness(properties);
    }

    @Property(tries = 2_000)
    void sameContextYieldsSameResult(@ForAll("contexts") AuthContext context) {
        RuleResult first = harness.evaluator.evaluate(context);
        RuleResult second = harness.evaluator.evaluate(context);
        assertThat(second).isEqualTo(first);
    }

    @Property(tries = 2_000)
    void decisionMatchesTheReferenceOracle(@ForAll("contexts") AuthContext context) {
        RuleResult result = harness.evaluator.evaluate(context);

        boolean overLimit = context.amount().amountMinor() > PER_TXN_CAP;
        boolean overVelocity = context.velocity().count() >= VELOCITY_MAX;
        Decision expected = (overLimit || overVelocity) ? Decision.DECLINE : Decision.APPROVE;

        assertThat(result.decision()).isEqualTo(expected);
        if (overLimit) {
            // per-txn has the lower priority value, so it decides ties with velocity.
            assertThat(result.reasonCode()).isEqualTo(ReasonCodes.LIMIT_EXCEEDED);
        } else if (overVelocity) {
            assertThat(result.reasonCode()).isEqualTo(ReasonCodes.VELOCITY);
        }
    }

    @Provide
    Arbitrary<AuthContext> contexts() {
        Arbitrary<Long> amounts = Arbitraries.longs().between(1L, 200_000L);
        Arbitrary<Integer> counts = Arbitraries.integers().between(0, 10);
        Arbitrary<Long> windowSums = Arbitraries.longs().between(0L, 500_000L);
        Arbitrary<String> mccs = Arbitraries.of("5411", "7995", "5812", "6011");

        return Combinators.combine(amounts, counts, windowSums, mccs)
                .as((amount, count, sum, mcc) -> new AuthContext(
                        AccountId.of("11111111-1111-1111-1111-111111111111"),
                        usd(amount),
                        mcc,
                        "merchant-1",
                        Instant.parse("2026-06-01T12:00:00Z"),
                        usd(1_000_000),
                        new VelocitySnapshot(count, usd(sum))));
    }
}
