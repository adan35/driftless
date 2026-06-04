package io.driftless.rules.internal;

import static io.driftless.rules.RuleFixtures.mccBlock;
import static io.driftless.rules.RuleFixtures.perTxnLimit;
import static io.driftless.rules.RuleFixtures.periodicCap;
import static io.driftless.rules.RuleFixtures.usd;
import static io.driftless.rules.RuleFixtures.velocityAmount;
import static io.driftless.rules.RuleFixtures.velocityCount;

import io.driftless.common.id.AccountId;
import io.driftless.rules.api.AuthContext;
import io.driftless.rules.api.RuleResult;
import io.driftless.rules.api.VelocitySnapshot;
import io.driftless.rules.config.RuleProperties;
import java.time.Instant;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * JMH micro-benchmark of {@link RuleEvaluator#evaluate} producing the full sample-time latency
 * distribution (p50/p90/p99/p99.9/max) over a representative rule set and mixed workload.
 *
 * <p>This is the deeper-profiling companion to {@link RuleEngineLatencyTest} (which owns the asserting
 * gate that runs in {@code verify}). It is not wired into the build — run it on demand to regenerate
 * the numbers recorded in {@code BENCHMARK.md}:
 *
 * <pre>{@code
 *   mvnw.cmd -pl rules -am -DskipTests test-compile
 *   mvnw.cmd -pl rules exec:java -Dexec.classpathScope=test \
 *       -Dexec.mainClass=io.driftless.rules.internal.RuleEngineBenchmark
 * }</pre>
 *
 * or via the JMH uber-jar if one is built. Reported in microseconds for resolution; the gate is
 * single-digit milliseconds.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(1)
public class RuleEngineBenchmark {

    private RuleEvaluator evaluator;
    private AuthContext[] workload;
    private int cursor;

    @Setup
    public void setUp() {
        RuleProperties properties = new RuleProperties();
        properties.getPerTransactionLimits().add(perTxnLimit("per-txn-500", 10, 50_000));
        properties.getPeriodicCaps().add(periodicCap("daily-2000", 20, 200_000));
        properties.getVelocity().add(velocityCount("vel-count-10", 30, 10));
        properties.getVelocity().add(velocityAmount("vel-sum-1000", 40, 100_000));
        properties.getMcc().add(mccBlock("mcc-block", 50, "7995", "6011", "4829", "6051"));

        RuleCache cache = new RuleCache(properties, new RuleCompiler());
        cache.refresh();
        this.evaluator = new RuleEvaluator(cache);
        this.workload = buildWorkload();
        this.cursor = 0;
    }

    @Benchmark
    public RuleResult evaluate() {
        AuthContext context = workload[cursor];
        cursor = (cursor + 1) & (workload.length - 1);
        return evaluator.evaluate(context);
    }

    private static AuthContext[] buildWorkload() {
        Random random = new Random(42);
        AccountId account = AccountId.of("11111111-1111-1111-1111-111111111111");
        Instant at = Instant.parse("2026-06-01T12:00:00Z");
        String[] mccs = {"5411", "5812", "5999", "7995", "6011"};

        AuthContext[] workload = new AuthContext[1_024]; // power of two for the cheap mask above
        for (int i = 0; i < workload.length; i++) {
            workload[i] = new AuthContext(
                    account,
                    usd(100L + random.nextInt(80_000)),
                    mccs[random.nextInt(mccs.length)],
                    "merchant-" + (i % 16),
                    at,
                    usd(1_000_000),
                    new VelocitySnapshot(random.nextInt(12), usd(random.nextInt(250_000))));
        }
        return workload;
    }

    public static void main(String[] args) throws RunnerException {
        Options options = new OptionsBuilder()
                .include(RuleEngineBenchmark.class.getSimpleName())
                .build();
        new Runner(options).run();
    }
}
