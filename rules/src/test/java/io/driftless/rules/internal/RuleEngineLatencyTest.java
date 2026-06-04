package io.driftless.rules.internal;

import static io.driftless.rules.RuleFixtures.mccBlock;
import static io.driftless.rules.RuleFixtures.perTxnLimit;
import static io.driftless.rules.RuleFixtures.periodicCap;
import static io.driftless.rules.RuleFixtures.usd;
import static io.driftless.rules.RuleFixtures.velocityAmount;
import static io.driftless.rules.RuleFixtures.velocityCount;
import static org.assertj.core.api.Assertions.assertThat;

import io.driftless.common.id.AccountId;
import io.driftless.rules.api.AuthContext;
import io.driftless.rules.api.VelocitySnapshot;
import io.driftless.rules.config.RuleProperties;
import java.time.Instant;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The p99 latency gate (Spec 05's headline acceptance criterion). A self-contained, recorded load
 * test: it drives a representative rule set with a mix of approving and declining contexts, records
 * the per-call latency of every {@link RuleEvaluator#evaluate} after a warm-up, and <strong>asserts
 * p99 is single-digit milliseconds</strong>. The measured percentiles are logged so QA can copy the
 * numbers into {@code BENCHMARK.md}.
 *
 * <p>This runs in {@code verify}, so a regression that blows the budget fails the build. It measures
 * in-process steady-state latency (the engine is pure and in-memory), which is exactly the quantity
 * the saga pays on the hot path.
 */
class RuleEngineLatencyTest {

    private static final Logger log = LoggerFactory.getLogger(RuleEngineLatencyTest.class);

    private static final int WARMUP_ITERATIONS = 50_000;
    private static final int MEASURED_ITERATIONS = 500_000;
    private static final double P99_BUDGET_MS = 10.0;

    @Test
    void p99IsSingleDigitMilliseconds() {
        EngineHarness harness = new EngineHarness(representativeRules());
        AuthContext[] workload = representativeWorkload();

        // Warm up the JIT and caches so we measure steady-state, not first-call, latency.
        long blackHole = 0;
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            blackHole += harness.evaluator
                    .evaluate(workload[i % workload.length])
                    .decision()
                    .ordinal();
        }

        long[] nanos = new long[MEASURED_ITERATIONS];
        for (int i = 0; i < MEASURED_ITERATIONS; i++) {
            AuthContext context = workload[i % workload.length];
            long start = System.nanoTime();
            blackHole += harness.evaluator.evaluate(context).decision().ordinal();
            nanos[i] = System.nanoTime() - start;
        }
        assertThat(blackHole).isNotNegative(); // keep the JIT from eliding the loop

        Arrays.sort(nanos);
        double p50 = percentileMs(nanos, 50.0);
        double p90 = percentileMs(nanos, 90.0);
        double p99 = percentileMs(nanos, 99.0);
        double p999 = percentileMs(nanos, 99.9);
        double max = nanos[nanos.length - 1] / 1_000_000.0;

        log.info(
                "rule-engine evaluate latency over {} measured calls ({} rules): "
                        + "p50={}ms p90={}ms p99={}ms p99.9={}ms max={}ms",
                MEASURED_ITERATIONS,
                harness.activeRuleCount(),
                fmt(p50),
                fmt(p90),
                fmt(p99),
                fmt(p999),
                fmt(max));

        assertThat(p99)
                .as("p99 of evaluate() must be single-digit milliseconds (budget %.1fms)", P99_BUDGET_MS)
                .isLessThan(P99_BUDGET_MS);
    }

    private static RuleProperties representativeRules() {
        RuleProperties properties = new RuleProperties();
        properties.getPerTransactionLimits().add(perTxnLimit("per-txn-500", 10, 50_000));
        properties.getPeriodicCaps().add(periodicCap("daily-2000", 20, 200_000));
        properties.getVelocity().add(velocityCount("vel-count-10", 30, 10));
        properties.getVelocity().add(velocityAmount("vel-sum-1000", 40, 100_000));
        properties.getMcc().add(mccBlock("mcc-block", 50, "7995", "6011", "4829", "6051"));
        return properties;
    }

    /**
     * A spread of contexts — clean approvals plus declines on each rule type — so the measurement
     * covers both the full-scan approve path and early-exit decline paths.
     */
    private static AuthContext[] representativeWorkload() {
        Random random = new Random(42);
        AccountId account = AccountId.of("11111111-1111-1111-1111-111111111111");
        Instant at = Instant.parse("2026-06-01T12:00:00Z");
        String[] mccs = {"5411", "5812", "5999", "7995", "6011"};

        AuthContext[] workload = new AuthContext[1_024];
        for (int i = 0; i < workload.length; i++) {
            long amount = 100L + random.nextInt(80_000);
            int count = random.nextInt(12);
            long windowSum = random.nextInt(250_000);
            String mcc = mccs[random.nextInt(mccs.length)];
            workload[i] = new AuthContext(
                    account,
                    usd(amount),
                    mcc,
                    "merchant-" + (i % 16),
                    at,
                    usd(1_000_000),
                    new VelocitySnapshot(count, usd(windowSum)));
        }
        return workload;
    }

    private static double percentileMs(long[] sortedNanos, double percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sortedNanos.length) - 1;
        index = Math.max(0, Math.min(index, sortedNanos.length - 1));
        return sortedNanos[index] / 1_000_000.0;
    }

    private static String fmt(double ms) {
        return String.format("%.4f", ms);
    }
}
