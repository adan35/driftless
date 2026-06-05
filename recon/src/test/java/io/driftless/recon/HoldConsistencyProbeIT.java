package io.driftless.recon;

import static org.assertj.core.api.Assertions.assertThat;

import io.driftless.auth.api.AuthorizationStatus;
import io.driftless.auth.api.AuthorizationView;
import io.driftless.auth.api.HoldStatus;
import io.driftless.auth.internal.AuthorizeCommand;
import io.driftless.auth.internal.persistence.HoldEntity;
import io.driftless.common.id.AccountId;
import io.driftless.recon.api.CheckResult;
import io.driftless.recon.api.OffenderType;
import io.driftless.recon.api.ReconCheck;
import io.driftless.recon.api.ReconciliationResult;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Regression probe for the {@code HOLD_CONSISTENCY} check (Spec 07, review finding C1). It constructs
 * a DANGLING HOLD fixture — an ACTIVE hold whose authorization is terminal (REVERSED) — and asserts
 * the continuous reconciliation job now DETECTS it (from an independent source of truth: the
 * authorization's status) and NAMES the offending hold, authorization and account. It also asserts
 * the check PASSES on clean data, so it is a genuine, falsifiable detector and not a tautology.
 */
class HoldConsistencyProbeIT extends AbstractReconIT {

    @Test
    void holdConsistencyDetectsAndNamesADanglingHold() {
        AccountId account = openFundedAccount(100_000);
        PARTNER.setAuthorizeMode(ControllablePartner.AuthorizeMode.APPROVE);

        // A real, AUTHORIZED authorization with one ACTIVE hold.
        AuthorizationView view = saga.authorize(
                new AuthorizeCommand(account, usd(10_000), APPROVE_MCC, MERCHANT, Optional.empty()),
                UUID.randomUUID().toString());
        assertThat(view.status()).isEqualTo(AuthorizationStatus.AUTHORIZED);
        UUID authId = view.id();
        assertThat(activeHoldTotal(account)).isEqualTo(10_000);
        UUID holdId = holds.findByAuthorizationIdAndStatus(authId, HoldStatus.ACTIVE)
                .get(0)
                .getId();

        // Simulate a BUG / crash residue: flip the authorization to a terminal REVERSED state but
        // leave its hold ACTIVE (a dangling hold). Done via the auth table (NOT journal_entry, which
        // the immutability trigger protects). No journal row changes => global balance is untouched.
        jdbcTemplate.update("UPDATE \"authorization\" SET status = 'REVERSED' WHERE id = ?", authId);

        try {
            ReconciliationResult result = reconciliationService.run();
            CheckResult hold = result.checks().stream()
                    .filter(c -> c.check() == ReconCheck.HOLD_CONSISTENCY)
                    .findFirst()
                    .orElseThrow();

            // The dangling hold is now DETECTED: the check fails and measures the dangling amount.
            assertThat(hold.passed())
                    .as("HOLD_CONSISTENCY must detect a dangling ACTIVE hold on a terminal authorization")
                    .isFalse();
            assertThat(hold.driftMinor()).isEqualTo(10_000L);
            assertThat(result.passed()).isFalse();

            // The offending hold, authorization AND account are named for RCA.
            assertThat(result.offenders())
                    .as("the dangling hold is named")
                    .anyMatch(
                            o -> o.type() == OffenderType.HOLD && o.reference().equals(holdId.toString()));
            assertThat(result.offenders())
                    .as("the terminal authorization is named")
                    .anyMatch(o -> o.type() == OffenderType.AUTHORIZATION
                            && o.reference().equals(authId.toString()));
            assertThat(result.offenders())
                    .as("the account whose available is wrongly reduced is named")
                    .anyMatch(o -> o.type() == OffenderType.ACCOUNT
                            && o.reference().equals(account.value().toString()));

            // No monetary drift was introduced: GLOBAL_ZERO and PER_TRANSACTION still hold.
            assertThat(globalSignedSum()).isZero();
            assertThat(result.checks())
                    .filteredOn(c -> c.check() == ReconCheck.GLOBAL_ZERO || c.check() == ReconCheck.PER_TRANSACTION)
                    .allMatch(CheckResult::passed);

            // Cross-check: the dangling hold is really still ACTIVE in committed state.
            List<HoldEntity> activeHolds = holds.findByAuthorizationIdAndStatus(authId, HoldStatus.ACTIVE);
            assertThat(activeHolds).hasSize(1);
        } finally {
            // Clean up the dangling residue so the shared container is not polluted.
            jdbcTemplate.update("DELETE FROM hold WHERE authorization_id = ?", authId);
            jdbcTemplate.update("DELETE FROM \"authorization\" WHERE id = ?", authId);
        }
    }

    @Test
    void holdConsistencyPassesOnCleanData() {
        AccountId account = openFundedAccount(100_000);
        PARTNER.setAuthorizeMode(ControllablePartner.AuthorizeMode.APPROVE);

        // A clean AUTHORIZED authorization: exactly one ACTIVE hold that cross-foots its amount.
        AuthorizationView view = saga.authorize(
                new AuthorizeCommand(account, usd(10_000), APPROVE_MCC, MERCHANT, Optional.empty()),
                UUID.randomUUID().toString());
        assertThat(view.status()).isEqualTo(AuthorizationStatus.AUTHORIZED);
        UUID authId = view.id();

        try {
            ReconciliationResult result = reconciliationService.run();
            CheckResult hold = result.checks().stream()
                    .filter(c -> c.check() == ReconCheck.HOLD_CONSISTENCY)
                    .findFirst()
                    .orElseThrow();

            assertThat(hold.passed())
                    .as("HOLD_CONSISTENCY passes when each AUTHORIZED authorization holds exactly its amount")
                    .isTrue();
            assertThat(hold.driftMinor()).isZero();
        } finally {
            // Reverse cleanly so the shared container ends with no ACTIVE holds.
            saga.reverse(authId, UUID.randomUUID().toString());
        }
    }
}
