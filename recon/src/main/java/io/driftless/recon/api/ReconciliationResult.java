package io.driftless.recon.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The structured, persisted result of one reconciliation run — the evidence Driftless links to when
 * it claims provable zero drift, and the read model Spec 08's dashboard renders.
 *
 * <p>A run is {@link #passed()} only when every {@link CheckResult} held. On any failure the
 * implicated {@link #offenders()} are surfaced so a human can do a root-cause analysis and decide a
 * <em>deliberate</em> compensating post — reconciliation never edits the ledger to "fix" drift.
 *
 * @param id the run's stable identifier (also the persistence key)
 * @param ranAt business time the run executed, from the injected {@code Clock}
 * @param passed {@code true} when every check held
 * @param totalDriftMinor sum of absolute drift across all checks, in minor units
 * @param checks the per-check outcomes, one per {@link ReconCheck}
 * @param offenders the implicated entities across all failed checks (empty on a clean run)
 */
public record ReconciliationResult(
        UUID id,
        Instant ranAt,
        boolean passed,
        long totalDriftMinor,
        List<CheckResult> checks,
        List<Offender> offenders) {

    public ReconciliationResult {
        checks = List.copyOf(checks);
        offenders = List.copyOf(offenders);
    }
}
