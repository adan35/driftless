package io.driftless.recon;

import static org.assertj.core.api.Assertions.assertThat;

import io.driftless.common.id.AccountId;
import io.driftless.recon.api.LoadProfile;
import io.driftless.recon.api.LoadResult;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The load generator sustains a configurable rate and reports throughput + latency. */
class LoadGeneratorIT extends AbstractReconIT {

    @Test
    void sustainsAConfiguredMixAndReportsThroughputAndLatency() {
        PARTNER.setAuthorizeMode(ControllablePartner.AuthorizeMode.APPROVE);
        List<AccountId> accounts =
                List.of(openFundedAccount(2_000_000), openFundedAccount(2_000_000), openFundedAccount(2_000_000));

        LoadProfile profile = LoadProfile.defaults(60, 6, 99L);
        LoadResult result = loadGenerator.run(profile, accounts, APPROVE_MCC, BLOCKED_MCC);

        assertThat(result.totalOperations()).isEqualTo(60);
        assertThat(result.failed()).isZero();
        // The mix did real work: approvals dominate, with some declines, captures and reversals.
        assertThat(result.approved()).isPositive();
        assertThat(result.declined()).isPositive();
        assertThat(result.captures() + result.reversals()).isPositive();
        // Throughput + latency are recorded (metrics-ready numbers for the dashboard).
        assertThat(result.throughputPerSecond()).isPositive();
        assertThat(result.p50LatencyMillis()).isGreaterThanOrEqualTo(0L);
        assertThat(result.p99LatencyMillis()).isGreaterThanOrEqualTo(result.p50LatencyMillis());
        assertThat(result.maxLatencyMillis()).isGreaterThanOrEqualTo(result.p99LatencyMillis());

        settleSweep();
        assertThat(reconciliationService.run().passed()).isTrue();
    }

    @Test
    void honoursAConfiguredTargetRate() {
        PARTNER.setAuthorizeMode(ControllablePartner.AuthorizeMode.APPROVE);
        List<AccountId> accounts = List.of(openFundedAccount(1_000_000));

        // 20 ops throttled to ~40/s on a single worker => the run cannot finish faster than ~0.45s.
        LoadProfile throttled = new LoadProfile(20, 1, 40, 0.0, 1.0, 0.0, 1_000L, 5_000L, 3L);
        LoadResult result = loadGenerator.run(throttled, accounts, APPROVE_MCC, BLOCKED_MCC);

        assertThat(result.totalOperations()).isEqualTo(20);
        assertThat(result.throughputPerSecond()).isLessThanOrEqualTo(60.0); // rate was respected (with margin)
        assertThat(result.elapsedMillis()).isGreaterThanOrEqualTo(400L);

        settleSweep();
        assertThat(globalSignedSum()).isZero();
    }
}
