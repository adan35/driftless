package io.driftless.partnersim.fault;

import static org.assertj.core.api.Assertions.assertThat;

import io.driftless.partnersim.partner.dto.AuthorizeRequest;
import io.driftless.partnersim.partner.state.RequestStateView;
import io.driftless.partnersim.support.SimulatorIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * QA edge-case probe (Spec 04): the precise semantics of the canonical drift trigger
 * {@code FAIL_BEFORE_RESPONSE}, and what a saga retry against it observes.
 *
 * <p>Spec 04 requires the simulator be able to "fail after doing the work but before responding" and
 * to return "duplicate/late responses" -- these tests pin down the side-effect bookkeeping the Spec 07
 * fault harness will rely on, including the fact that a fail-before-response is deliberately NOT
 * recorded for idempotent replay (so the work is genuinely re-doable, which is what makes it a drift
 * trigger the saga's compensation must neutralise).
 */
class FailBeforeResponseSemanticsTest extends SimulatorIntegrationTest {

    private AuthorizeRequest auth(String requestId) {
        return new AuthorizeRequest(requestId, "card-ref", 1000, "USD", "5411", "merchant-1");
    }

    private void applyFault(FaultProfile profile) {
        client.post()
                .uri("/control/faults")
                .body(profile)
                .exchange()
                .expectStatus()
                .isOk();
    }

    private HttpStatus authorizeStatus(String requestId) {
        return HttpStatus.valueOf(client.post()
                .uri("/partner/authorize")
                .body(auth(requestId))
                .exchange()
                .returnResult(Void.class)
                .getStatus()
                .value());
    }

    private RequestStateView state(String requestId) {
        return client.get()
                .uri("/partner/state/{id}", requestId)
                .exchange()
                .expectBody(RequestStateView.class)
                .returnResult()
                .getResponseBody();
    }

    /**
     * Deterministic, next-1-request fail-before-response, then heal. The first call performs the work
     * (side effect recorded, delivered=false) but 500s; the state read proves the work happened with no
     * delivered response.
     */
    @Test
    void failBeforeResponseRecordsUndeliveredSideEffectAndReturns500() {
        applyFault(new FaultProfile(PartnerRoute.AUTHORIZE, FaultMode.FAIL_BEFORE_RESPONSE, 0, 0, 0.0, 1, 0, null));

        assertThat(authorizeStatus("fbr-probe-1")).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        RequestStateView view = state("fbr-probe-1");
        assertThat(view).isNotNull();
        assertThat(view.known()).isTrue();
        assertThat(view.responseDelivered()).isFalse();
        assertThat(view.sideEffectsPerformed()).isTrue();
        assertThat(view.sideEffects()).hasSize(1);
        assertThat(view.sideEffects().getFirst().delivered()).isFalse();
        // No completion record was stored, so the read view carries no response object.
        assertThat(view.response()).isNull();
    }

    /**
     * The drift-trigger property made explicit: a fail-before-response is NOT memoised for replay.
     * After the fault auto-heals (applyToNext=1), retrying the SAME request id re-executes and records
     * a SECOND side effect. This is intentional -- the partner genuinely did the work twice -- and is
     * exactly the condition the saga's idempotent compensating reversal must absorb without drift.
     *
     * <p>This documents that the simulator's idempotency guarantee covers only COMPLETED requests; a
     * mid-flight failure is replayable by design, so the saga (not the simulator) owns exactly-once.
     */
    @Test
    void retryingAfterFailBeforeResponseReExecutesAndRecordsASecondSideEffect() {
        applyFault(new FaultProfile(PartnerRoute.AUTHORIZE, FaultMode.FAIL_BEFORE_RESPONSE, 0, 0, 0.0, 1, 0, null));

        // First call: fails after doing the work.
        assertThat(authorizeStatus("fbr-retry")).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(state("fbr-retry").sideEffects()).hasSize(1);

        // Fault has auto-healed (next-1). The saga retries the SAME id; it now succeeds...
        assertThat(authorizeStatus("fbr-retry")).isEqualTo(HttpStatus.OK);

        // ...and a SECOND side effect is recorded: the simulator did NOT dedup the half-done request.
        RequestStateView view = state("fbr-retry");
        assertThat(view.sideEffects()).hasSize(2);
        assertThat(view.responseDelivered()).isTrue();
        // The successful retry recorded a delivered side effect on top of the earlier undelivered one.
        assertThat(view.sideEffects().get(0).delivered()).isFalse();
        assertThat(view.sideEffects().get(1).delivered()).isTrue();
    }

    /**
     * Probe for the documented mismatch between {@link FaultMode#TIMEOUT}'s javadoc ("the work is NOT
     * performed") and the implemented behaviour. The implementation delays past the threshold and then
     * completes normally -- recording a delivered side effect -- so a state read after a timeout shows
     * the work WAS performed. This is harmless (the simulator holds no money), but the caller's bounded
     * timeout will already have fired, so from the saga's view a timeout + a late server-side success
     * is itself a drift trigger (it must compensate even though the partner ultimately "succeeded").
     */
    @Test
    void timeoutModeActuallyCompletesTheWorkServerSideDespiteTheJavadocClaim() {
        long delay = 1200; // past a typical ~800ms bounded timeout
        applyFault(new FaultProfile(PartnerRoute.AUTHORIZE, FaultMode.TIMEOUT, delay, 0, 0.0, 1, 0, null));

        long start = System.nanoTime();
        assertThat(authorizeStatus("to-probe")).isEqualTo(HttpStatus.OK);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(delay);

        RequestStateView view = state("to-probe");
        // Contrary to the FaultMode.TIMEOUT javadoc, the side effect IS recorded and delivered.
        assertThat(view.sideEffectsPerformed()).isTrue();
        assertThat(view.responseDelivered()).isTrue();
        assertThat(view.sideEffects()).hasSize(1);
        assertThat(view.sideEffects().getFirst().delivered()).isTrue();
    }
}
