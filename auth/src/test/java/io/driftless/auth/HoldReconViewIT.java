package io.driftless.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.driftless.auth.api.AuthorizationStatus;
import io.driftless.auth.internal.AuthorizeCommand;
import io.driftless.auth.spi.DanglingHold;
import io.driftless.auth.spi.HoldBalanceMismatch;
import io.driftless.auth.spi.HoldReconView;
import io.driftless.common.id.AccountId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Exercises the Spec 03 {@link HoldReconView} SPI — the independent hold-vs-authorization view the
 * Spec 07 reconciliation job relies on. It asserts a clean saga state reconciles, that a dangling
 * ACTIVE hold on a terminal authorization is surfaced, and that an AUTHORIZED authorization missing
 * its hold is surfaced as a cross-foot mismatch — all from the authorizations' own statuses, never
 * from the ledger's {@code balanceOf}.
 */
class HoldReconViewIT extends AbstractAuthIT {

    @Autowired
    HoldReconView holdRecon;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private AuthorizeCommand approveCmd(AccountId account, long amountMinor) {
        return new AuthorizeCommand(account, usd(amountMinor), APPROVE_MCC, MERCHANT, Optional.empty());
    }

    @Test
    void cleanAuthorizedStateHasNoDanglingHoldsAndCrossFoots() {
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.APPROVE);
        AccountId account = openFundedAccount(100_000);
        UUID authId = saga.authorize(
                        approveCmd(account, 30_000), UUID.randomUUID().toString())
                .id();

        try {
            assertThat(danglingFor(authId)).isEmpty();
            assertThat(mismatchFor(account)).isEmpty();
        } finally {
            saga.reverse(authId, UUID.randomUUID().toString());
        }
    }

    @Test
    void detectsADanglingHoldOnATerminalAuthorization() {
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.APPROVE);
        AccountId account = openFundedAccount(100_000);
        UUID authId = saga.authorize(
                        approveCmd(account, 30_000), UUID.randomUUID().toString())
                .id();

        // Crash residue: authorization terminal (REVERSED) but its hold left ACTIVE.
        jdbcTemplate.update("UPDATE \"authorization\" SET status = 'REVERSED' WHERE id = ?", authId);

        try {
            List<DanglingHold> dangling = danglingFor(authId);
            assertThat(dangling).hasSize(1);
            assertThat(dangling.get(0).authorizationStatus()).isEqualTo(AuthorizationStatus.REVERSED);
            assertThat(dangling.get(0).amountMinor()).isEqualTo(30_000L);
            assertThat(dangling.get(0).accountId()).isEqualTo(account.value());

            // A dangling hold also breaks the cross-foot: ACTIVE holds (30_000) vs AUTHORIZED (0).
            List<HoldBalanceMismatch> mismatch = mismatchFor(account);
            assertThat(mismatch).hasSize(1);
            assertThat(mismatch.get(0).differenceMinor()).isEqualTo(30_000L);
        } finally {
            jdbcTemplate.update("DELETE FROM hold WHERE authorization_id = ?", authId);
            jdbcTemplate.update("DELETE FROM \"authorization\" WHERE id = ?", authId);
        }
    }

    @Test
    void detectsAnAuthorizedAuthorizationThatLostItsHold() {
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.APPROVE);
        AccountId account = openFundedAccount(100_000);
        UUID authId = saga.authorize(
                        approveCmd(account, 25_000), UUID.randomUUID().toString())
                .id();

        // Residue: authorization stays AUTHORIZED but its hold was wrongly RELEASED.
        jdbcTemplate.update("UPDATE hold SET status = 'RELEASED' WHERE authorization_id = ?", authId);

        try {
            assertThat(danglingFor(authId)).isEmpty();
            List<HoldBalanceMismatch> mismatch = mismatchFor(account);
            assertThat(mismatch).hasSize(1);
            // ACTIVE holds (0) vs AUTHORIZED amounts (25_000): off by -25_000.
            assertThat(mismatch.get(0).activeHoldMinor()).isZero();
            assertThat(mismatch.get(0).authorizedAmountMinor()).isEqualTo(25_000L);
            assertThat(mismatch.get(0).differenceMinor()).isEqualTo(-25_000L);
        } finally {
            jdbcTemplate.update("DELETE FROM hold WHERE authorization_id = ?", authId);
            jdbcTemplate.update("DELETE FROM \"authorization\" WHERE id = ?", authId);
        }
    }

    private List<DanglingHold> danglingFor(UUID authId) {
        return holdRecon.findDanglingHolds().stream()
                .filter(d -> d.authorizationId().equals(authId))
                .toList();
    }

    private List<HoldBalanceMismatch> mismatchFor(AccountId account) {
        return holdRecon.findHoldBalanceMismatches().stream()
                .filter(m -> m.accountId().equals(account.value()))
                .toList();
    }
}
