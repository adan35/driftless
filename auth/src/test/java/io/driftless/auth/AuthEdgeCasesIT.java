package io.driftless.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.driftless.auth.api.AuthorizationStatus;
import io.driftless.auth.api.IllegalAuthorizationState;
import io.driftless.auth.internal.AuthorizeCommand;
import io.driftless.auth.internal.persistence.AuthorizationEntity;
import io.driftless.auth.internal.persistence.HoldEntity;
import io.driftless.common.id.AccountId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Edge/branch paths of the saga: partner decline, validation, illegal transitions, failing compensation. */
class AuthEdgeCasesIT extends AbstractAuthIT {

    private AuthorizeCommand approveCmd(AccountId account, long amountMinor) {
        return new AuthorizeCommand(account, usd(amountMinor), APPROVE_MCC, MERCHANT, Optional.empty());
    }

    @Test
    void partnerDeclineReleasesTheHoldAndMovesNoMoney() {
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.DECLINE);
        AccountId account = openFundedAccount(100_000);

        var view = saga.authorize(approveCmd(account, 30_000), key());

        assertThat(view.status()).isEqualTo(AuthorizationStatus.DECLINED);
        assertThat(activeHoldTotal(account)).isZero();
        assertThat(available(account)).isEqualTo(100_000);
        assertThat(posted(account)).isEqualTo(100_000);
    }

    @Test
    void captureForMoreThanAuthorizedIsRejected() {
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.APPROVE);
        AccountId account = openFundedAccount(100_000);
        UUID authId = saga.authorize(approveCmd(account, 30_000), key()).id();

        assertThatThrownBy(() -> saga.capture(authId, Optional.of(usd(50_000)), key()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(saga.view(authId).status()).isEqualTo(AuthorizationStatus.AUTHORIZED);
    }

    @Test
    void captureOfANonAuthorizedAuthorizationIsAConflict() {
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.APPROVE);
        AccountId account = openFundedAccount(100_000);
        UUID authId = saga.authorize(approveCmd(account, 30_000), key()).id();
        saga.reverse(authId, key()); // now REVERSED

        assertThatThrownBy(() -> saga.capture(authId, Optional.empty(), key()))
                .isInstanceOf(IllegalAuthorizationState.class);
    }

    @Test
    void reverseOfADeclinedAuthorizationIsAConflict() {
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.DECLINE);
        AccountId account = openFundedAccount(100_000);
        UUID authId = saga.authorize(approveCmd(account, 30_000), key()).id();

        assertThatThrownBy(() -> saga.reverse(authId, key())).isInstanceOf(IllegalAuthorizationState.class);
    }

    @Test
    void compensationReleasesTheHoldButStaysCompensatingWhenThePartnerReverseKeepsFailing() {
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.FAIL_BEFORE_RESPONSE);
        PARTNER.setReverseFails(true); // the bounded-retry partner reverse exhausts its attempts
        AccountId account = openFundedAccount(100_000);

        var view = saga.authorize(approveCmd(account, 30_000), key());

        // Hold released at once (available restored), but the row is NOT driven to terminal REVERSED: the
        // partner-side authorization could not be confirmed reversed, so the obligation stays COMPENSATING
        // for the recovery sweep to retry to completion — it can never silently drop (review C2).
        assertThat(view.status()).isEqualTo(AuthorizationStatus.COMPENSATING);
        assertThat(activeHoldTotal(account)).isZero(); // hold released regardless of partner reverse
        assertThat(saga.outstandingPartnerObligations()).isGreaterThanOrEqualTo(1L);
        assertThat(saga.danglingPartnerReverseFinalizations()).isZero(); // never sealed without confirmation
        assertZeroDrift(account);
    }

    @Test
    void compensationEventuallyReversesThePartnerOnceItRecovers() {
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.FAIL_BEFORE_RESPONSE);
        PARTNER.setReverseFails(true); // partner reverse is down on the in-line attempt
        AccountId account = openFundedAccount(100_000);

        var view = saga.authorize(approveCmd(account, 30_000), key());
        assertThat(view.status()).isEqualTo(AuthorizationStatus.COMPENSATING);

        // The partner recovers; the sweep re-drives the durable obligation to completion.
        PARTNER.setReverseFails(false);
        recoverySweep.sweep();

        assertThat(saga.view(view.id()).status()).isEqualTo(AuthorizationStatus.REVERSED);
        assertThat(PARTNER.reversed(view.id().toString())).isTrue();
        assertThat(saga.danglingPartnerReverseFinalizations()).isZero();
        assertZeroDrift(account);
    }

    @Test
    void reverseOfAnAuthorizedHoldWithoutAPartnerRefStillReleases() {
        AccountId account = openFundedAccount(100_000);
        UUID authId = UUID.randomUUID();
        Instant now = Instant.now();
        // An AUTHORIZED authorization with no partnerRef (the best-effort partner reverse is skipped).
        authorizations.save(new AuthorizationEntity(
                authId,
                account.value(),
                30_000,
                CURRENCY,
                APPROVE_MCC,
                MERCHANT,
                null,
                "card-ref",
                AuthorizationStatus.AUTHORIZED,
                "edge-key-" + authId,
                now));
        holds.save(new HoldEntity(UUID.randomUUID(), authId, account.value(), 30_000, CURRENCY, now));

        var view = saga.reverse(authId, key());

        assertThat(view.status()).isEqualTo(AuthorizationStatus.REVERSED);
        assertThat(activeHoldTotal(account)).isZero();
    }

    @Test
    void captureWithMismatchedCurrencyIsRejected() {
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.APPROVE);
        AccountId account = openFundedAccount(100_000);
        UUID authId = saga.authorize(approveCmd(account, 30_000), key()).id();

        assertThatThrownBy(() ->
                        saga.capture(authId, Optional.of(io.driftless.common.money.Money.of(10_000, "EUR")), key()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reverseSwallowsAFailingPartnerReverseAndStillReleasesTheHold() {
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.APPROVE);
        AccountId account = openFundedAccount(100_000);
        UUID authId = saga.authorize(approveCmd(account, 30_000), key()).id();
        PARTNER.setReverseFails(true); // the best-effort partner reverse throws; the hold release must still commit

        var view = saga.reverse(authId, key());

        assertThat(view.status()).isEqualTo(AuthorizationStatus.REVERSED);
        assertThat(activeHoldTotal(account)).isZero();
    }

    @Test
    void reverseOfAnAuthorizedAuthorizationWithNoActiveHoldIsStillTerminal() {
        AccountId account = openFundedAccount(100_000);
        UUID authId = UUID.randomUUID();
        // AUTHORIZED with a partnerRef but no hold row (e.g. hold already released out of band).
        AuthorizationEntity auth = new AuthorizationEntity(
                authId,
                account.value(),
                30_000,
                CURRENCY,
                APPROVE_MCC,
                MERCHANT,
                null,
                "card-ref",
                AuthorizationStatus.AUTHORIZED,
                "edge-key-nohold-" + authId,
                Instant.now());
        auth.recordPartnerRef("AUTH-existing");
        authorizations.save(auth);

        var view = saga.reverse(authId, key());

        assertThat(view.status()).isEqualTo(AuthorizationStatus.REVERSED);
    }

    @Test
    void authorizeCommandRejectsNonPositiveAmount() {
        AccountId account = AccountId.newId();
        assertThatThrownBy(() -> new AuthorizeCommand(account, usd(0), APPROVE_MCC, MERCHANT, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
