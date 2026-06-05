package io.driftless.observability.metrics;

import io.driftless.rules.api.AuthContext;
import io.driftless.rules.api.RuleEngine;
import io.driftless.rules.api.RuleResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * A transparent timing decorator over the frozen {@link RuleEngine} contract. It records every {@code
 * evaluate} call into a {@link Timer} with a percentile histogram, so the dashboard can render the
 * <strong>p99</strong> and verify the single-digit-millisecond claim, and counts decisions by reason
 * code. It then returns the delegate's result unchanged — the rules module's pure, I/O-free hot path
 * is untouched; only a nanosecond timer sample is taken around it.
 *
 * <p>Wired as the {@code @Primary} {@link RuleEngine} (see {@code ObservabilityMetricsConfig}) so the
 * auth saga evaluates through it without the rules module knowing the meter exists.
 */
public class TimedRuleEngine implements RuleEngine {

    private static final String REASON_APPROVE = "APPROVE";

    private final RuleEngine delegate;
    private final Timer timer;
    private final MeterRegistry registry;

    /**
     * Per-(decision, reason) decision counters, resolved once and reused. The decision/reason space is
     * small and bounded ({@code APPROVE} plus the fixed machine reason codes), so steady-state {@code
     * evaluate()} does a single map lookup and {@code increment()} — no per-call {@code Tags} allocation
     * or registry registration on the single-digit-ms hot path the spec protects.
     */
    private final ConcurrentHashMap<String, Counter> decisionCounters = new ConcurrentHashMap<>();

    public TimedRuleEngine(RuleEngine delegate, MeterRegistry registry) {
        this.delegate = delegate;
        this.registry = registry;
        this.timer = Timer.builder(MetricNames.RULES_EVALUATION)
                .description("Rule engine evaluate() latency")
                .publishPercentileHistogram()
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    @Override
    public RuleResult evaluate(AuthContext context) {
        long startNanos = System.nanoTime();
        try {
            RuleResult result = delegate.evaluate(context);
            count(result);
            return result;
        } finally {
            timer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        }
    }

    private void count(RuleResult result) {
        String decision = result.decision().name();
        String reason = result.isApproved() ? REASON_APPROVE : result.reasonCode();
        decisionCounters
                .computeIfAbsent(decision + '\u0000' + reason, key -> Counter.builder(MetricNames.RULES_DECISIONS)
                        .tag(MetricNames.TAG_DECISION, decision)
                        .tag(MetricNames.TAG_REASON, reason)
                        .description("Rule decisions by reason code")
                        .register(registry))
                .increment();
    }
}
