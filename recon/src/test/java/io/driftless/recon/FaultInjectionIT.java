package io.driftless.recon;

import static org.assertj.core.api.Assertions.assertThat;

import io.driftless.auth.api.AuthorizationStatus;
import io.driftless.auth.api.AuthorizationView;
import io.driftless.auth.internal.AuthorizeCommand;
import io.driftless.auth.internal.persistence.AuthorizationEntity;
import io.driftless.auth.internal.persistence.HoldEntity;
import io.driftless.common.id.AccountId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * After load runs WITH injected faults — timeout, fail-before-response, duplicates, and a simulated
 * process-kill/recovery — reconciliation STILL reports zero drift, demonstrating the system self-heals
 * via compensation + idempotency. Recon is the proof, not the fix.
 */
class FaultInjectionIT extends AbstractReconIT {

    private AuthorizeCommand approveCmd(AccountId account, long amountMinor) {
        return new AuthorizeCommand(account, usd(amountMinor), APPROVE_MCC, MERCHANT, Optional.empty());
    }

    @Test
    void partnerTimeoutsSelfHealToZeroDrift() {
        AccountId account = openFundedAccount(1_000_000);
        PARTNER.setAuthorizeMode(ControllablePartner.AuthorizeMode.TIMEOUT);

        for (int i = 0; i < 4; i++) {
            AuthorizationView view = saga.authorize(approveCmd(account, 30_000), key());
            assertThat(view.status()).isEqualTo(AuthorizationStatus.COMPENSATING);
        }
        settleSweep();

        assertThat(reconciliationService.run().passed()).isTrue();
        assertThat(activeHoldTotal(account)).isZero();
        assertThat(available(account)).isEqualTo(1_000_000);
        assertThat(globalSignedSum()).isZero();
    }

    @Test
    void failBeforeResponseSelfHealsToZeroDrift() {
        AccountId account = openFundedAccount(1_000_000);
        PARTNER.setAuthorizeMode(ControllablePartner.AuthorizeMode.FAIL_BEFORE_RESPONSE);

        AuthorizationView view = saga.authorize(approveCmd(account, 40_000), key());
        assertThat(view.status()).isEqualTo(AuthorizationStatus.REVERSED);
        // The partner did the work; compensation discovered and reversed it (no double effect).
        assertThat(PARTNER.reversed(view.id().toString())).isTrue();
        settleSweep();

        assertThat(reconciliationService.run().passed()).isTrue();
        assertThat(globalSignedSum()).isZero();
    }

    @Test
    void duplicateRequestsAreIdempotentAndProduceNoDrift() {
        AccountId account = openFundedAccount(1_000_000);
        PARTNER.setAuthorizeMode(ControllablePartner.AuthorizeMode.APPROVE);

        String authKey = key();
        String captureKey = key();
        // Duplicate the WHOLE authorize+capture sequence with the same keys (a retried client / at-least-once).
        UUID authId = saga.authorize(approveCmd(account, 50_000), authKey).id();
        saga.authorize(approveCmd(account, 50_000), authKey); // duplicate authorize
        saga.capture(authId, Optional.empty(), captureKey);
        saga.capture(authId, Optional.empty(), captureKey); // duplicate capture

        // Exactly one settlement posted despite the duplicates.
        assertThat(ledger.balanceOf(account).posted().amountMinor()).isEqualTo(950_000);
        settleSweep();
        assertThat(reconciliationService.run().passed()).isTrue();
        assertThat(globalSignedSum()).isZero();
    }

    @Test
    void simulatedProcessKillIsRecoveredWithZeroDrift() {
        // A process killed mid-saga leaves an in-flight authorization + ACTIVE hold (the residue of a crash);
        // the recovery sweep adopts it and drives it to terminal with no dangling hold and no drift.
        AccountId account = openFundedAccount(1_000_000);
        UUID authId = insertCrashResidue(account, 60_000);
        assertThat(activeHoldTotal(account)).isEqualTo(60_000);

        settleSweep();

        assertThat(saga.view(authId).status()).isEqualTo(AuthorizationStatus.REVERSED);
        assertThat(activeHoldTotal(account)).isZero();
        assertThat(reconciliationService.run().passed()).isTrue();
        assertThat(globalSignedSum()).isZero();
    }

    /** Persist an AUTHORIZING authorization with a matching ACTIVE hold whose partner never confirmed — a crash residue. */
    private UUID insertCrashResidue(AccountId account, long amountMinor) {
        UUID authId = UUID.randomUUID();
        Instant past = Instant.now().minusSeconds(3600);
        AuthorizationEntity auth = new AuthorizationEntity(
                authId,
                account.value(),
                amountMinor,
                CURRENCY,
                APPROVE_MCC,
                MERCHANT,
                null,
                "card-ref",
                AuthorizationStatus.AUTHORIZING,
                "recovery-key-" + authId,
                past);
        authorizations.save(auth);
        holds.save(new HoldEntity(UUID.randomUUID(), authId, account.value(), amountMinor, CURRENCY, past));
        return authId;
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
