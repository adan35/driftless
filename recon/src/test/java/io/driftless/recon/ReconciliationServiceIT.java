package io.driftless.recon;

import static org.assertj.core.api.Assertions.assertThat;

import io.driftless.common.id.AccountId;
import io.driftless.recon.api.CheckResult;
import io.driftless.recon.api.LoadProfile;
import io.driftless.recon.api.ReconCheck;
import io.driftless.recon.api.ReconciliationResult;
import io.driftless.recon.internal.ReconciliationScheduler;
import io.driftless.recon.internal.config.ReconProperties;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The reconciliation job runs on schedule and on demand and persists a structured result; a clean
 * load run reports zero drift; and a stuck outbox event is detected.
 */
class ReconciliationServiceIT extends AbstractReconIT {

    @Autowired
    ReconciliationScheduler scheduler;

    @Autowired
    ReconProperties properties;

    @Test
    void onDemandRunProducesAPersistedPassingResult() {
        openFundedAccount(100_000);

        ReconciliationResult result = reconciliationService.run();

        assertThat(result.passed()).isTrue();
        assertThat(result.totalDriftMinor()).isZero();
        assertThat(result.checks()).extracting(CheckResult::check).containsExactlyInAnyOrder(ReconCheck.values());
        assertThat(result.checks()).allMatch(CheckResult::passed);

        // Persisted + exposed via latest() for Spec 08's dashboard.
        Optional<ReconciliationResult> latest = reconciliationService.latest();
        assertThat(latest).isPresent();
        assertThat(latest.get().id()).isEqualTo(result.id());
    }

    @Test
    void scheduledRunPersistsAResult() {
        long before = reconciliationService.latest().map(r -> 1).orElse(0);

        scheduler.scheduledRun();

        assertThat(reconciliationService.latest()).isPresent();
        // A second scheduled run produces a distinct, newer result row.
        ReconciliationResult first = reconciliationService.latest().orElseThrow();
        scheduler.scheduledRun();
        ReconciliationResult second = reconciliationService.latest().orElseThrow();
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(before).isLessThanOrEqualTo(1);
    }

    @Test
    void afterACleanLoadRunReconciliationReportsZeroDrift() {
        List<AccountId> accounts = List.of(openFundedAccount(1_000_000), openFundedAccount(1_000_000));
        PARTNER.setAuthorizeMode(ControllablePartner.AuthorizeMode.APPROVE);

        loadGenerator.run(LoadProfile.defaults(40, 4, 7L), accounts, APPROVE_MCC, BLOCKED_MCC);
        settleSweep();

        ReconciliationResult result = reconciliationService.run();
        assertThat(result.passed()).as("no faults injected => zero drift").isTrue();
        assertThat(result.totalDriftMinor()).isZero();
        assertThat(globalSignedSum()).isZero();
    }

    @Test
    void stuckPendingOutboxEventIsDetectedAsAReliabilityDefect() {
        // An approve emits an AUTHORIZED lifecycle outbox event (PENDING; the relay is disabled in tests).
        PARTNER.setAuthorizeMode(ControllablePartner.AuthorizeMode.APPROVE);
        AccountId account = openFundedAccount(100_000);
        saga.authorize(
                new io.driftless.auth.internal.AuthorizeCommand(
                        account, usd(10_000), APPROVE_MCC, MERCHANT, Optional.empty()),
                java.util.UUID.randomUUID().toString());

        Duration original = properties.getOutboxStuckAfter();
        try {
            // Treat any PENDING event as immediately stuck so the check bites deterministically.
            properties.setOutboxStuckAfter(Duration.ZERO);
            ReconciliationResult result = reconciliationService.run();

            assertThat(result.passed()).isFalse();
            CheckResult outbox = result.checks().stream()
                    .filter(c -> c.check() == ReconCheck.OUTBOX_CONSISTENCY)
                    .findFirst()
                    .orElseThrow();
            assertThat(outbox.passed()).isFalse();
            assertThat(result.offenders()).anyMatch(o -> o.type() == io.driftless.recon.api.OffenderType.OUTBOX_EVENT);
            // Outbox staleness is a reliability defect, not monetary drift: the money checks still hold.
            assertThat(globalSignedSum()).isZero();
        } finally {
            properties.setOutboxStuckAfter(original);
        }
    }
}
