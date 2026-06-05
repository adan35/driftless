package io.driftless.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.driftless.auth.api.AuthorizationStatus;
import io.driftless.auth.api.AuthorizationView;
import io.driftless.auth.internal.AuthorizeCommand;
import io.driftless.common.id.AccountId;
import io.driftless.common.id.TokenId;
import io.driftless.ledger.api.Page;
import io.driftless.tokens.api.CreateTokenCommand;
import io.driftless.tokens.api.Token;
import io.driftless.tokens.api.TokenService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** End-to-end saga behaviour against real Postgres + the controllable partner stub. */
class AuthSagaIT extends AbstractAuthIT {

    @Autowired
    TokenService tokens;

    private AuthorizeCommand approveCmd(AccountId account, long amountMinor) {
        return new AuthorizeCommand(account, usd(amountMinor), APPROVE_MCC, MERCHANT, Optional.empty());
    }

    @Test
    void successfulAuthorizePlacesHoldReducingAvailableButNotPosted() {
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.APPROVE);
        AccountId account = openFundedAccount(100_000);

        AuthorizationView view = saga.authorize(approveCmd(account, 30_000), key());

        assertThat(view.status()).isEqualTo(AuthorizationStatus.AUTHORIZED);
        assertThat(view.partnerRef()).isPresent();
        assertThat(posted(account)).isEqualTo(100_000); // posted UNCHANGED by a hold
        assertThat(available(account)).isEqualTo(70_000); // available DROPPED by the hold
        assertThat(activeHoldTotal(account)).isEqualTo(30_000);
        assertZeroDrift(account);
    }

    @Test
    void captureConvertsHoldToSettledPostingAndReleasesHold() {
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.APPROVE);
        AccountId account = openFundedAccount(100_000);
        UUID authId = saga.authorize(approveCmd(account, 30_000), key()).id();

        AuthorizationView captured = saga.capture(authId, Optional.empty(), key());

        assertThat(captured.status()).isEqualTo(AuthorizationStatus.CAPTURED);
        assertThat(posted(account)).isEqualTo(70_000); // posted moved on capture
        assertThat(available(account)).isEqualTo(70_000); // hold released; available == posted
        assertThat(activeHoldTotal(account)).isZero();
        assertZeroDrift(account);
    }

    @Test
    void reverseFromAuthorizedReleasesHoldAndLeavesPostedUntouched() {
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.APPROVE);
        AccountId account = openFundedAccount(100_000);
        UUID authId = saga.authorize(approveCmd(account, 30_000), key()).id();
        int entriesBefore = ledger.entriesFor(account, new Page(0, 50)).size();

        AuthorizationView reversed = saga.reverse(authId, key());

        assertThat(reversed.status()).isEqualTo(AuthorizationStatus.REVERSED);
        assertThat(posted(account)).isEqualTo(100_000); // never moved
        assertThat(available(account)).isEqualTo(100_000); // hold released
        assertThat(activeHoldTotal(account)).isZero();
        // No new journal entries: a hold reversal moves no posted money.
        assertThat(ledger.entriesFor(account, new Page(0, 50))).hasSize(entriesBefore);
        assertZeroDrift(account);
    }

    @Test
    void reverseFromCapturedPostsInverseAsNewTransactionLeavingOriginalIntact() {
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.APPROVE);
        AccountId account = openFundedAccount(100_000);
        UUID authId = saga.authorize(approveCmd(account, 30_000), key()).id();
        saga.capture(authId, Optional.empty(), key());
        int entriesAfterCapture = ledger.entriesFor(account, new Page(0, 50)).size();

        AuthorizationView reversed = saga.reverse(authId, key());

        assertThat(reversed.status()).isEqualTo(AuthorizationStatus.REVERSED);
        assertThat(posted(account)).isEqualTo(100_000); // inverse settlement restored posted
        // The reversal is a NEW compensating entry on the cardholder account, not an edit.
        assertThat(ledger.entriesFor(account, new Page(0, 50)).size()).isEqualTo(entriesAfterCapture + 1);
        assertZeroDrift(account);
    }

    @Test
    void ruleDeclineMovesNoMoneyAndPlacesNoHold() {
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.APPROVE);
        AccountId account = openFundedAccount(100_000);

        AuthorizationView view = saga.authorize(
                new AuthorizeCommand(account, usd(30_000), BLOCKED_MCC, MERCHANT, Optional.empty()), key());

        assertThat(view.status()).isEqualTo(AuthorizationStatus.DECLINED);
        assertThat(view.declineReason()).isPresent();
        assertThat(posted(account)).isEqualTo(100_000);
        assertThat(available(account)).isEqualTo(100_000);
        assertThat(activeHoldTotal(account)).isZero();
    }

    @Test
    void partnerTimeoutReleasesTheHoldAndStaysCompensatingUntilSettled() {
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.TIMEOUT);
        AccountId account = openFundedAccount(100_000);

        AuthorizationView view = saga.authorize(approveCmd(account, 30_000), key());

        // The partner timed out (outcome indeterminate): the hold is released immediately so available is
        // restored, but the row stays COMPENSATING (not terminal) until the partner side is settled — so a
        // late partner success can still be reversed by the sweep (review C3). No drift either way.
        assertThat(view.status()).isEqualTo(AuthorizationStatus.COMPENSATING);
        assertThat(activeHoldTotal(account)).isZero(); // hold released
        assertThat(available(account)).isEqualTo(100_000); // available restored
        assertThat(posted(account)).isEqualTo(100_000);
        assertThat(saga.danglingPartnerReverseFinalizations()).isZero();
        assertZeroDrift(account);
    }

    @Test
    void failBeforeResponseCompensatesAndAbsorbsTheLatePartnerWork() {
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.FAIL_BEFORE_RESPONSE);
        AccountId account = openFundedAccount(100_000);

        AuthorizationView view = saga.authorize(approveCmd(account, 30_000), key());

        assertThat(view.status()).isEqualTo(AuthorizationStatus.REVERSED);
        assertThat(activeHoldTotal(account)).isZero();
        assertThat(posted(account)).isEqualTo(100_000);
        // The partner did the work (recorded a partnerRef); compensation discovered it and reversed it,
        // so a late partner success is absorbed with no double effect.
        assertThat(PARTNER.reversed(view.id().toString())).isTrue();
        assertZeroDrift(account);
    }

    @Test
    void inactiveTokenDeclinesWithoutMovingMoney() {
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.APPROVE);
        AccountId account = openFundedAccount(100_000);
        Token token = tokens.create(CreateTokenCommand.forCard("card-ref-1")); // born INACTIVE

        AuthorizationView view = saga.authorize(
                new AuthorizeCommand(account, usd(30_000), APPROVE_MCC, MERCHANT, Optional.of(token.id())), key());

        assertThat(view.status()).isEqualTo(AuthorizationStatus.DECLINED);
        assertThat(view.declineReason()).contains("TOKEN_INACTIVE");
        assertThat(activeHoldTotal(account)).isZero();
    }

    @Test
    void activeTokenIsAuthorized() {
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.APPROVE);
        AccountId account = openFundedAccount(100_000);
        TokenId tokenId = TokenId.newId();
        tokens.create(CreateTokenCommand.forCard("card-ref-2", tokenId));
        tokens.activate(tokenId);

        AuthorizationView view = saga.authorize(
                new AuthorizeCommand(account, usd(30_000), APPROVE_MCC, MERCHANT, Optional.of(tokenId)), key());

        assertThat(view.status()).isEqualTo(AuthorizationStatus.AUTHORIZED);
        assertThat(view.tokenId()).contains(tokenId.value());
    }

    @Test
    void replayingAuthorizeWithSameKeyReturnsOriginalAndPlacesNoSecondHold() {
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.APPROVE);
        AccountId account = openFundedAccount(100_000);
        AuthorizeCommand cmd = approveCmd(account, 30_000);
        String key = key();

        AuthorizationView first = saga.authorize(cmd, key);
        long countAfterFirst = authorizations.count();
        AuthorizationView replay = saga.authorize(cmd, key);

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(authorizations.count()).isEqualTo(countAfterFirst); // no new authorization row
        assertThat(activeHoldTotal(account)).isEqualTo(30_000); // exactly one hold
    }

    @Test
    void replayingCaptureWithSameKeyPostsOnlyOnce() {
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.APPROVE);
        AccountId account = openFundedAccount(100_000);
        UUID authId = saga.authorize(approveCmd(account, 30_000), key()).id();
        String captureKey = key();

        saga.capture(authId, Optional.empty(), captureKey);
        long postedAfterFirst = posted(account);
        saga.capture(authId, Optional.empty(), captureKey);

        assertThat(posted(account)).isEqualTo(postedAfterFirst); // no double settlement
        assertThat(posted(account)).isEqualTo(70_000);
        assertZeroDrift(account);
    }

    @Test
    void replayingReverseWithSameKeyIsIdempotent() {
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.APPROVE);
        AccountId account = openFundedAccount(100_000);
        UUID authId = saga.authorize(approveCmd(account, 30_000), key()).id();
        String reverseKey = key();

        saga.reverse(authId, reverseKey);
        AuthorizationView replay = saga.reverse(authId, reverseKey);

        assertThat(replay.status()).isEqualTo(AuthorizationStatus.REVERSED);
        assertThat(activeHoldTotal(account)).isZero();
        assertThat(posted(account)).isEqualTo(100_000);
    }

    @Test
    void partialCaptureSettlesOnlyTheCapturedAmount() {
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.APPROVE);
        AccountId account = openFundedAccount(100_000);
        UUID authId = saga.authorize(approveCmd(account, 30_000), key()).id();

        saga.capture(authId, Optional.of(usd(20_000)), key());

        assertThat(posted(account)).isEqualTo(80_000); // only 20k settled
        assertThat(activeHoldTotal(account)).isZero();
        assertZeroDrift(account);
    }

    @Test
    void reverseOfAPartialCaptureRestoresExactlyTheCapturedAmount() {
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.APPROVE);
        AccountId account = openFundedAccount(100_000);
        AccountId settlement = settlementAccountId(java.util.Currency.getInstance(CURRENCY));
        long cardBefore = posted(account);
        long settleBefore = posted(settlement);

        UUID authId = saga.authorize(approveCmd(account, 30_000), key()).id();
        // Capture only 10k of the 30k authorized; the reversal must invert exactly 10k (review C1), not 30k.
        saga.capture(authId, Optional.of(usd(10_000)), key());
        assertThat(posted(account)).isEqualTo(cardBefore - 10_000);

        var reversed = saga.reverse(authId, key());

        assertThat(reversed.status()).isEqualTo(AuthorizationStatus.REVERSED);
        // Both the cardholder and the settlement account return exactly to their pre-capture values: the
        // reversal posted the inverse of the CAPTURED 10k, so no over-credit of the missing 20k.
        assertThat(posted(account)).isEqualTo(cardBefore);
        assertThat(posted(settlement)).isEqualTo(settleBefore);
        assertThat(activeHoldTotal(account)).isZero();
        assertZeroDrift(account);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
