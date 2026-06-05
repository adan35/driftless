package io.driftless.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.driftless.auth.api.AuthorizationStatus;
import io.driftless.auth.api.IllegalAuthorizationState;
import io.driftless.auth.internal.AuthorizeCommand;
import io.driftless.common.id.AccountId;
import io.driftless.idempotency.api.IdempotencyConflict;
import io.driftless.ledger.api.JournalEntry;
import io.driftless.ledger.api.Page;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * QA falsification suite (Spec 03) — independent attempts to break the three invariants, especially
 * under failure, beyond the developer's happy/edge coverage. Each test is a Given/When/Then probe.
 */
class AuthFalsificationIT extends AbstractAuthIT {

    private AuthorizeCommand approveCmd(AccountId account, long amountMinor) {
        return new AuthorizeCommand(account, usd(amountMinor), APPROVE_MCC, MERCHANT, Optional.empty());
    }

    // F1 — IMMUTABILITY: after reverse-of-capture the ORIGINAL entries are byte-identical (records equal).
    @Test
    void reverseOfCaptureLeavesOriginalEntriesByteIdentical() {
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.APPROVE);
        AccountId account = openFundedAccount(100_000);
        AccountId settlement = settlementAccountId(java.util.Currency.getInstance(CURRENCY));
        UUID authId = saga.authorize(approveCmd(account, 30_000), key()).id();
        saga.capture(authId, Optional.empty(), key());

        List<JournalEntry> cardBefore = new ArrayList<>(ledger.entriesFor(account, new Page(0, 100)));
        List<JournalEntry> settleBefore = new ArrayList<>(ledger.entriesFor(settlement, new Page(0, 100)));

        saga.reverse(authId, key());

        List<JournalEntry> cardAfter = ledger.entriesFor(account, new Page(0, 100));
        List<JournalEntry> settleAfter = ledger.entriesFor(settlement, new Page(0, 100));
        // Every pre-existing entry is still present and equal (append-only; no edit of the original).
        assertThat(cardAfter).containsAll(cardBefore);
        assertThat(settleAfter).containsAll(settleBefore);
        // The reversal added exactly one new compensating entry per affected account.
        assertThat(cardAfter).hasSize(cardBefore.size() + 1);
        assertThat(settleAfter).hasSize(settleBefore.size() + 1);
        assertZeroDrift(account);
    }

    // F2 — DOUBLE-EFFECT: two captures under DIFFERENT idempotency keys must not double-settle.
    @Test
    void secondCaptureUnderADifferentKeyIsRejectedNoDoubleSettlement() {
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.APPROVE);
        AccountId account = openFundedAccount(100_000);
        UUID authId = saga.authorize(approveCmd(account, 30_000), key()).id();

        saga.capture(authId, Optional.empty(), key());
        long postedAfterFirst = posted(account);

        assertThatThrownBy(() -> saga.capture(authId, Optional.empty(), key()))
                .isInstanceOf(IllegalAuthorizationState.class);

        assertThat(posted(account)).isEqualTo(postedAfterFirst); // no second settlement
        assertThat(posted(account)).isEqualTo(70_000);
        assertZeroDrift(account);
    }

    // F3 — DOUBLE-EFFECT: two reverses under DIFFERENT keys must not double-post the inverse.
    @Test
    void secondReverseOfCapturedUnderADifferentKeyIsRejected() {
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.APPROVE);
        AccountId account = openFundedAccount(100_000);
        UUID authId = saga.authorize(approveCmd(account, 30_000), key()).id();
        saga.capture(authId, Optional.empty(), key());
        saga.reverse(authId, key()); // CAPTURED -> REVERSED, inverse posted, posted back to 100_000

        assertThat(posted(account)).isEqualTo(100_000);
        assertThatThrownBy(() -> saga.reverse(authId, key())) // different key, already terminal
                .isInstanceOf(IllegalAuthorizationState.class);
        assertThat(posted(account)).isEqualTo(100_000); // not double-reversed
        assertZeroDrift(account);
    }

    // F4 — IDEMPOTENCY: same key + different body (capture amount) must conflict (the 409 contract).
    @Test
    void captureSameKeyDifferentAmountConflicts() {
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.APPROVE);
        AccountId account = openFundedAccount(100_000);
        UUID authId = saga.authorize(approveCmd(account, 30_000), key()).id();
        String captureKey = key();

        saga.capture(authId, Optional.of(usd(20_000)), captureKey);

        assertThatThrownBy(() -> saga.capture(authId, Optional.of(usd(10_000)), captureKey))
                .isInstanceOf(IdempotencyConflict.class);
        assertThat(posted(account)).isEqualTo(80_000); // only the first 20k settled
        assertZeroDrift(account);
    }

    // F5 — BOUNDARY: a zero-amount capture is rejected and moves nothing.
    @Test
    void zeroAmountCaptureIsRejected() {
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.APPROVE);
        AccountId account = openFundedAccount(100_000);
        UUID authId = saga.authorize(approveCmd(account, 30_000), key()).id();

        assertThatThrownBy(() -> saga.capture(authId, Optional.of(usd(0)), key()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(saga.view(authId).status()).isEqualTo(AuthorizationStatus.AUTHORIZED);
        assertThat(activeHoldTotal(account)).isEqualTo(30_000); // hold still stands
    }

    // F6 — IDEMPOTENCY: a blank Idempotency-Key at the saga boundary is rejected (no silent default key).
    @Test
    void blankIdempotencyKeyIsRejectedOnEveryMutatingStep() {
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.APPROVE);
        AccountId account = openFundedAccount(100_000);
        assertThatThrownBy(() -> saga.authorize(approveCmd(account, 30_000), "  "))
                .isInstanceOf(IllegalArgumentException.class);
        UUID authId = saga.authorize(approveCmd(account, 30_000), key()).id();
        assertThatThrownBy(() -> saga.capture(authId, Optional.empty(), ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> saga.reverse(authId, null)).isInstanceOf(IllegalArgumentException.class);
    }

    // F7 — THE GATE: a mixed sequence (authorize/capture/reverse + an injected timeout) keeps global sum 0.
    @Test
    void mixedRandomishSequenceKeepsGlobalSignedSumZero() {
        AccountId account = openFundedAccount(1_000_000);

        // a) approve + capture full
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.APPROVE);
        UUID a1 = saga.authorize(approveCmd(account, 50_000), key()).id();
        saga.capture(a1, Optional.empty(), key());

        // b) approve + partial capture
        UUID a2 = saga.authorize(approveCmd(account, 40_000), key()).id();
        saga.capture(a2, Optional.of(usd(15_000)), key());

        // c) approve + reverse from authorized (no money moved)
        UUID a3 = saga.authorize(approveCmd(account, 25_000), key()).id();
        saga.reverse(a3, key());

        // d) approve + capture + reverse-of-capture (inverse posted)
        UUID a4 = saga.authorize(approveCmd(account, 30_000), key()).id();
        saga.capture(a4, Optional.empty(), key());
        saga.reverse(a4, key());

        // e) injected partner timeout -> compensating reversal (hold released, stays COMPENSATING)
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.TIMEOUT);
        AuthorizationStatus timedOut =
                saga.authorize(approveCmd(account, 60_000), key()).status();
        assertThat(timedOut).isEqualTo(AuthorizationStatus.COMPENSATING);

        // f) rule decline (no hold, no money)
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.APPROVE);
        saga.authorize(new AuthorizeCommand(account, usd(10_000), BLOCKED_MCC, MERCHANT, Optional.empty()), key());

        // Invariant: signed sum of EVERY journal entry in the currency is exactly 0.
        assertThat(journalEntries.globalSignedSumMinor(CURRENCY)).isZero();
        // No dangling holds for any terminal authorization on this account.
        assertThat(activeHoldTotal(account)).isZero();
    }

    /**
     * F8 — LATE-RESPONSE ABSORPTION under a real TIMEOUT (the actual "$20K" scenario): the partner is
     * slow, the saga times out and releases the hold but stays {@code COMPENSATING}, then the partner
     * eventually APPROVES and records a partner-side authorization. The recovery sweep must observe that
     * late approval and send the idempotent partner reverse, so the partner-side authorization is
     * cancelled — no dangling partner-side hold, no drift. This is the corrected C2/C3 behaviour and a
     * durable regression test for it.
     */
    @Test
    void timeoutThenLatePartnerSuccessIsAbsorbedByTheSweep() throws InterruptedException {
        PARTNER.setAuthorizeMode(ConfigurablePartnerServer.AuthorizeMode.TIMEOUT);
        PARTNER.setTimeoutSleepMillis(1_500L);
        AccountId account = openFundedAccount(100_000);

        var view = saga.authorize(approveCmd(account, 30_000), key());

        // In-line: hold released and available restored at once, but the row is NOT terminal — the slow
        // partner has not recorded yet, so the outcome is indeterminate and the row stays COMPENSATING.
        assertThat(view.status()).isEqualTo(AuthorizationStatus.COMPENSATING);
        assertThat(activeHoldTotal(account)).isZero();
        assertThat(available(account)).isEqualTo(100_000);
        assertZeroDrift(account);
        assertThat(PARTNER.reversed(view.id().toString())).isFalse(); // nothing to reverse yet

        // Wait for the slow partner thread to finish and record its (late) approval, then drive the sweep.
        Thread.sleep(2_000L);
        recoverySweep.sweep();

        assertThat(saga.view(view.id()).status()).isEqualTo(AuthorizationStatus.REVERSED);
        assertThat(PARTNER.reversed(view.id().toString()))
                .as("late partner success after a TIMEOUT is absorbed (partner-side authorization reversed)")
                .isTrue();
        // The operator marker must read zero: no compensation was sealed without a confirmed partner reverse.
        assertThat(saga.danglingPartnerReverseFinalizations()).isZero();
        assertZeroDrift(account);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
