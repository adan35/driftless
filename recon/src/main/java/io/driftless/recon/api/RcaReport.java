package io.driftless.recon.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A short, RCA-style report produced from a failed {@link ReconciliationResult}: what drifted, where
 * (the implicated accounts/transactions), and a candidate cause inferred from which checks failed.
 * It is descriptive only — remediation is a deliberate, human-decided compensating post.
 *
 * @param reconciliationId the run this report explains
 * @param generatedAt business time the report was produced
 * @param drifted {@code true} when the run failed (otherwise the report states "no drift")
 * @param totalDriftMinor total absolute drift across all checks, in minor units
 * @param failedChecks the checks that failed
 * @param offenders the implicated accounts/transactions/currencies/outbox events
 * @param candidateCause a one-line hypothesis for the most likely root cause
 * @param summary a human-readable narrative summary
 */
public record RcaReport(
        UUID reconciliationId,
        Instant generatedAt,
        boolean drifted,
        long totalDriftMinor,
        List<ReconCheck> failedChecks,
        List<Offender> offenders,
        String candidateCause,
        String summary) {

    public RcaReport {
        failedChecks = List.copyOf(failedChecks);
        offenders = List.copyOf(offenders);
    }
}
