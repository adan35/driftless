package io.driftless.recon.internal;

import io.driftless.recon.api.CheckResult;
import io.driftless.recon.api.RcaReport;
import io.driftless.recon.api.ReconCheck;
import io.driftless.recon.api.ReconciliationResult;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Produces a short, RCA-style report from a {@link ReconciliationResult}: what drifted, where (the
 * implicated accounts/transactions), and a candidate cause inferred from which checks failed.
 *
 * <p>Descriptive only — it proposes a hypothesis for a human to act on, it never remediates. The
 * candidate cause is ranked by severity: an unbalanced posting (a broken balance invariant) outranks
 * a hold-reconciliation gap (a dangling hold), which outranks a stuck outbox relay.
 */
@Component
@RequiredArgsConstructor
public class RcaReporter {

    private final Clock clock;

    /** Build the RCA report for a reconciliation run. */
    public RcaReport report(ReconciliationResult result) {
        List<ReconCheck> failed = result.checks().stream()
                .filter(c -> !c.passed())
                .map(CheckResult::check)
                .toList();
        boolean drifted = !result.passed();
        String candidateCause = candidateCause(failed);
        String summary = drifted
                ? "Reconciliation %s FAILED at %s: total drift %d minor units across %s; %d offending entit%s; candidate cause: %s"
                        .formatted(
                                result.id(),
                                result.ranAt(),
                                result.totalDriftMinor(),
                                failed,
                                result.offenders().size(),
                                result.offenders().size() == 1 ? "y" : "ies",
                                candidateCause)
                : "Reconciliation %s PASSED at %s: no drift detected.".formatted(result.id(), result.ranAt());
        return new RcaReport(
                result.id(),
                clock.instant(),
                drifted,
                result.totalDriftMinor(),
                failed,
                result.offenders(),
                candidateCause,
                summary);
    }

    private static String candidateCause(List<ReconCheck> failed) {
        if (failed.contains(ReconCheck.GLOBAL_ZERO) || failed.contains(ReconCheck.PER_TRANSACTION)) {
            return "an unbalanced journal posting bypassed the balanced post() path (direct write or a "
                    + "missing compensating leg); inspect the named transaction(s)";
        }
        if (failed.contains(ReconCheck.HOLD_CONSISTENCY)) {
            return "an active hold was not released on capture/reversal (a dangling hold on a terminal "
                    + "authorization), so available no longer equals posted minus active holds";
        }
        if (failed.contains(ReconCheck.OUTBOX_CONSISTENCY)) {
            return "the outbox relay is stuck (PENDING events older than the threshold); downstream "
                    + "events are not propagating";
        }
        return "no drift";
    }
}
