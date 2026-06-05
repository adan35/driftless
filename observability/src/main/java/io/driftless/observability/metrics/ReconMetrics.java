package io.driftless.observability.metrics;

import io.driftless.recon.api.CheckResult;
import io.driftless.recon.api.ReconCheck;
import io.driftless.recon.api.ReconciliationResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Binds the <strong>zero-drift</strong> gauges to the reconciliation read model.
 *
 * <p>It listens for the {@link ReconciliationResult} the Spec 07 {@code ReconciliationService}
 * publishes as a Spring application event after each run and snapshots the figures the dashboard
 * reads: the headline {@code driftless_recon_drift_amount} (which reads {@code 0} for a correct
 * ledger), the pass/fail state, the per-check outcomes, the stuck-outbox count and the run timestamp.
 * The gauges hold their last value between runs, so the dashboard is steady, not flapping to zero
 * between scrapes.
 *
 * <p>The drift gauge is seeded at {@code 0} and {@code passed} at {@code 1} so a freshly booted system
 * shows "green / $0" before its first reconciliation run completes.
 */
@Component
public class ReconMetrics {

    private static final Logger log = LoggerFactory.getLogger(ReconMetrics.class);

    private final AtomicLong driftMinor = new AtomicLong(0L);
    private final AtomicLong passed = new AtomicLong(1L);
    private final AtomicLong lastRunEpochSeconds = new AtomicLong(0L);
    private final AtomicLong stuckOutbox = new AtomicLong(0L);
    private final Map<ReconCheck, AtomicLong> checkPassed = new EnumMap<>(ReconCheck.class);

    private final Counter passRuns;
    private final Counter failRuns;

    public ReconMetrics(MeterRegistry registry) {
        Gauge.builder(MetricNames.RECON_DRIFT_AMOUNT, driftMinor, AtomicLong::doubleValue)
                .description("Headline reconciliation drift in minor units; 0 for a correct ledger")
                .baseUnit("minor_units")
                .register(registry);
        Gauge.builder(MetricNames.RECON_RUN_PASSED, passed, AtomicLong::doubleValue)
                .description("1 when the latest reconciliation run passed, 0 when it failed")
                .register(registry);
        Gauge.builder(MetricNames.RECON_LAST_RUN, lastRunEpochSeconds, AtomicLong::doubleValue)
                .description("Epoch-seconds of the latest reconciliation run")
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder(MetricNames.RECON_STUCK_OUTBOX, stuckOutbox, AtomicLong::doubleValue)
                .description("Stuck PENDING outbox events measured by the latest run")
                .register(registry);
        for (ReconCheck check : ReconCheck.values()) {
            AtomicLong state = new AtomicLong(1L);
            checkPassed.put(check, state);
            Gauge.builder(MetricNames.RECON_CHECK_PASSED, state, AtomicLong::doubleValue)
                    .tag(MetricNames.TAG_CHECK, check.name())
                    .description("1/0 per reconciliation check")
                    .register(registry);
        }
        this.passRuns = Counter.builder(MetricNames.RECON_RUNS)
                .tag(MetricNames.TAG_OUTCOME, "pass")
                .description("Reconciliation runs observed, by outcome")
                .register(registry);
        this.failRuns = Counter.builder(MetricNames.RECON_RUNS)
                .tag(MetricNames.TAG_OUTCOME, "fail")
                .description("Reconciliation runs observed, by outcome")
                .register(registry);
    }

    /**
     * Snapshot the latest reconciliation result onto the gauges, decoupled from the recon path.
     *
     * <p>Bound {@code AFTER_COMMIT} so it observes only persisted runs and can <strong>never</strong>
     * roll back the {@code reconciliation_result} that is the gate's evidence — the recon transaction
     * has already committed before this fires. {@code fallbackExecution = true} keeps it firing for
     * events published outside any transaction (e.g. tests that publish a synthetic failed result).
     * The body is additionally guarded: a metrics failure is logged and swallowed, so observation can
     * never propagate an exception back toward the reconciliation run.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onReconciliation(ReconciliationResult result) {
        try {
            driftMinor.set(result.totalDriftMinor());
            passed.set(result.passed() ? 1L : 0L);
            lastRunEpochSeconds.set(result.ranAt().getEpochSecond());
            for (CheckResult check : result.checks()) {
                AtomicLong state = checkPassed.get(check.check());
                if (state != null) {
                    state.set(check.passed() ? 1L : 0L);
                }
                if (check.check() == ReconCheck.OUTBOX_CONSISTENCY && !check.passed()) {
                    // The outbox check reports its backlog as the count of stuck events via driftMinor=0 but
                    // a non-zero "detail"; surface the stuck count directly from this check's failure.
                    stuckOutbox.set(stuckCountFrom(check));
                } else if (check.check() == ReconCheck.OUTBOX_CONSISTENCY) {
                    stuckOutbox.set(0L);
                }
            }
            if (result.passed()) {
                passRuns.increment();
            } else {
                failRuns.increment();
                log.warn(
                        "recon run {} FAILED drift={} — zero-drift dashboard will show red",
                        result.id(),
                        result.totalDriftMinor());
            }
        } catch (RuntimeException ex) {
            log.warn("failed to snapshot reconciliation result {} onto metrics — gauges unchanged", result.id(), ex);
        }
    }

    /** Best-effort parse of the stuck count the OUTBOX_CONSISTENCY check carries in its detail. */
    private static long stuckCountFrom(CheckResult check) {
        // detail format: "<n> stuck PENDING outbox event(s)"; fall back to 1 if it ever changes shape
        // or is absent (a future fail(...) could pass null — never NPE on observation, see I4/W2).
        String detail = check.detail();
        if (detail == null) {
            return 1L;
        }
        int space = detail.indexOf(' ');
        if (space > 0) {
            try {
                return Long.parseLong(detail.substring(0, space));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return 1L;
    }
}
