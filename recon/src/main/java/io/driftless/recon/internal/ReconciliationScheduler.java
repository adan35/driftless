package io.driftless.recon.internal;

import io.driftless.recon.api.ReconciliationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The continuous, scheduled balance-proof job: it drives {@link ReconciliationService#run()} on a
 * configurable cadence ({@code driftless.recon.schedule-interval}) so zero drift is proven
 * <em>continuously</em>, not just on demand. Each run persists its own result for the dashboard.
 *
 * <p>Tests set a very long interval so the timer effectively never fires and instead drive {@code
 * run()} directly for determinism — mirroring how the auth recovery sweep and outbox relay are
 * driven in tests.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationScheduler {

    private final ReconciliationService reconciliationService;

    @Scheduled(fixedDelayString = "${driftless.recon.schedule-interval:PT1M}")
    public void scheduledRun() {
        ReconciliationResult result = reconciliationService.run();
        if (!result.passed()) {
            log.warn(
                    "scheduled reconciliation {} detected drift={} — surfacing for RCA (no auto-remediation)",
                    result.id(),
                    result.totalDriftMinor());
        }
    }
}
