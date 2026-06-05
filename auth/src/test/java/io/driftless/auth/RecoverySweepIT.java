package io.driftless.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.driftless.auth.api.AuthorizationStatus;
import io.driftless.auth.internal.partner.PartnerClient;
import io.driftless.auth.internal.persistence.AuthorizationEntity;
import io.driftless.auth.internal.persistence.HoldEntity;
import io.driftless.common.id.AccountId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The crash-recovery sweep: a process killed mid-saga leaves an in-flight authorization; the sweep
 * adopts it and drives it to a consistent terminal state with no dangling hold and no drift.
 */
class RecoverySweepIT extends AbstractAuthIT {

    @Autowired
    PartnerClient partnerClient;

    @Test
    void sweepCompensatesAStuckAuthorizingWhosePartnerNeverConfirmed() {
        AccountId account = openFundedAccount(100_000);
        UUID authId = insertInFlight(account, AuthorizationStatus.AUTHORIZING, 30_000);
        assertThat(activeHoldTotal(account)).isEqualTo(30_000);
        settle();

        recoverySweep.sweep();

        assertThat(saga.view(authId).status()).isEqualTo(AuthorizationStatus.REVERSED);
        assertThat(activeHoldTotal(account)).isZero(); // no dangling hold
        assertThat(available(account)).isEqualTo(100_000);
        assertZeroDrift(account);
    }

    @Test
    void sweepFinalizesAStuckAuthorizingThatThePartnerHadApproved() {
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.APPROVE);
        AccountId account = openFundedAccount(100_000);
        UUID authId = UUID.randomUUID();
        // The partner DID approve before our process died; replicate its recorded state.
        partnerClient.authorize(authId.toString(), "card-ref", 30_000, CURRENCY, APPROVE_MCC, MERCHANT);
        insertInFlight(account, authId, AuthorizationStatus.AUTHORIZING, 30_000);
        settle();

        recoverySweep.sweep();

        assertThat(saga.view(authId).status()).isEqualTo(AuthorizationStatus.AUTHORIZED);
        assertThat(activeHoldTotal(account)).isEqualTo(30_000); // hold stands for an approved auth
    }

    @Test
    void sweepReleasesAStuckAuthorizingThatThePartnerHadDeclined() {
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.DECLINE);
        AccountId account = openFundedAccount(100_000);
        UUID authId = UUID.randomUUID();
        partnerClient.authorize(authId.toString(), "card-ref", 30_000, CURRENCY, APPROVE_MCC, MERCHANT);
        insertInFlight(account, authId, AuthorizationStatus.AUTHORIZING, 30_000);
        settle();

        recoverySweep.sweep();

        assertThat(saga.view(authId).status()).isEqualTo(AuthorizationStatus.DECLINED);
        assertThat(activeHoldTotal(account)).isZero();
    }

    @Test
    void sweepReDrivesAStuckCompensatingToReversed() {
        AccountId account = openFundedAccount(100_000);
        UUID authId = insertInFlight(account, AuthorizationStatus.COMPENSATING, 30_000);
        settle();

        recoverySweep.sweep();

        assertThat(saga.view(authId).status()).isEqualTo(AuthorizationStatus.REVERSED);
        assertThat(activeHoldTotal(account)).isZero();
        assertZeroDrift(account);
    }

    private UUID insertInFlight(AccountId account, AuthorizationStatus status, long amountMinor) {
        return insertInFlight(account, UUID.randomUUID(), status, amountMinor);
    }

    /** Persist an authorization stuck in an in-flight state with a matching ACTIVE hold — the residue of a crash. */
    private UUID insertInFlight(AccountId account, UUID authId, AuthorizationStatus status, long amountMinor) {
        // Backdate the row well past any late-partner grace: it is the residue of an earlier crash, so a
        // compensation may immediately conclude "no partner side effect" if the partner has no record.
        Instant now = Instant.now().minusSeconds(3600);
        AuthorizationEntity auth = new AuthorizationEntity(
                authId,
                account.value(),
                amountMinor,
                CURRENCY,
                APPROVE_MCC,
                MERCHANT,
                null,
                "card-ref",
                status,
                "recovery-key-" + authId,
                now);
        authorizations.save(auth);
        holds.save(new HoldEntity(UUID.randomUUID(), authId, account.value(), amountMinor, CURRENCY, now));
        return authId;
    }

    /** Let wall-clock advance past the (zero) stuck-after threshold so the inserted row is sweep-eligible. */
    private static void settle() {
        try {
            Thread.sleep(50L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
