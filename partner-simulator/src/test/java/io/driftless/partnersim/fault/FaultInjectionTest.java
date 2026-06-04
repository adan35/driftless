package io.driftless.partnersim.fault;

import static org.assertj.core.api.Assertions.assertThat;

import io.driftless.partnersim.partner.dto.AuthorizeRequest;
import io.driftless.partnersim.partner.dto.AuthorizeResponse;
import io.driftless.partnersim.partner.dto.CaptureRequest;
import io.driftless.partnersim.partner.dto.CaptureResponse;
import io.driftless.partnersim.partner.dto.ReverseRequest;
import io.driftless.partnersim.partner.dto.ReverseResponse;
import io.driftless.partnersim.partner.state.RequestStateView;
import io.driftless.partnersim.support.SimulatorIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Acceptance for the control plane and per-route fault modes, driven over real HTTP exactly as Spec
 * 03 / Spec 07 will drive the simulator.
 */
class FaultInjectionTest extends SimulatorIntegrationTest {

    private static final long TIMEOUT_THRESHOLD_MILLIS = 800;

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

    private AuthorizeResponse authorize(String requestId) {
        return client.post()
                .uri("/partner/authorize")
                .body(auth(requestId))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(AuthorizeResponse.class)
                .returnResult()
                .getResponseBody();
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

    @Test
    void latencyDelaysResponseByConfiguredAmount() {
        long delay = 600;
        applyFault(new FaultProfile(PartnerRoute.AUTHORIZE, FaultMode.LATENCY, delay, 0, 0.0, 0, 0, null));

        long start = System.nanoTime();
        AuthorizeResponse response = authorize("lat-1");
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(response).isNotNull();
        assertThat(response.approved()).isTrue();
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(delay);
    }

    @Test
    void failBeforeResponseRecordsSideEffectButReturnsNoSuccess() {
        applyFault(new FaultProfile(PartnerRoute.AUTHORIZE, FaultMode.FAIL_BEFORE_RESPONSE, 0, 0, 0.0, 0, 0, null));

        // The caller gets no success...
        assertThat(authorizeStatus("fbr-1")).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        // ...yet the simulator did the work server-side: the drift trigger, provable via its own state.
        RequestStateView view = state("fbr-1");
        assertThat(view).isNotNull();
        assertThat(view.known()).isTrue();
        assertThat(view.sideEffectsPerformed()).isTrue();
        assertThat(view.responseDelivered()).isFalse();
        assertThat(view.sideEffects()).hasSize(1);
        assertThat(view.sideEffects().getFirst().delivered()).isFalse();
        assertThat(view.sideEffects().getFirst().partnerRef()).isNotBlank();
    }

    @Test
    void timeoutModeDelaysPastTheCallersBoundedThreshold() {
        long delay = TIMEOUT_THRESHOLD_MILLIS + 400;
        applyFault(new FaultProfile(PartnerRoute.AUTHORIZE, FaultMode.TIMEOUT, delay, 0, 0.0, 0, 0, null));

        long start = System.nanoTime();
        AuthorizeResponse response = authorize("to-1");
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        // A caller with an 800ms bounded timeout (Spec 03) would have fired before this returns.
        assertThat(elapsedMillis).isGreaterThan(TIMEOUT_THRESHOLD_MILLIS);
        assertThat(response).isNotNull();
        assertThat(response.approved()).isTrue();
    }

    @Test
    void declineModeReturnsADeclinedResponse() {
        applyFault(new FaultProfile(PartnerRoute.AUTHORIZE, FaultMode.DECLINE, 0, 0, 1.0, 0, 7, "INSUFFICIENT_FUNDS"));

        AuthorizeResponse response = authorize("dec-1");

        assertThat(response).isNotNull();
        assertThat(response.approved()).isFalse();
        assertThat(response.declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void errorRateModeReturns500() {
        applyFault(new FaultProfile(PartnerRoute.AUTHORIZE, FaultMode.ERROR_RATE, 0, 0, 1.0, 0, 7, null));

        assertThat(authorizeStatus("err-1")).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void duplicateModeRespondsWithADuplicateMarker() {
        applyFault(new FaultProfile(PartnerRoute.AUTHORIZE, FaultMode.DUPLICATE, 0, 0, 0.0, 0, 0, null));

        AuthorizeResponse response = authorize("dup-1");

        assertThat(response).isNotNull();
        assertThat(response.approved()).isTrue();
        assertThat(response.duplicate()).isTrue();

        // The work was still recorded once and the original (non-duplicate) response is replayed.
        AuthorizeResponse replay = authorize("dup-1");
        assertThat(replay).isNotNull();
        assertThat(replay.duplicate()).isFalse();
        assertThat(replay.partnerRef()).isEqualTo(response.partnerRef());
    }

    @Test
    void lateResponsePerformsWorkThenRespondsAfterDelay() {
        long delay = 500;
        applyFault(new FaultProfile(PartnerRoute.AUTHORIZE, FaultMode.LATE_RESPONSE, delay, 0, 0.0, 0, 0, null));

        long start = System.nanoTime();
        AuthorizeResponse response = authorize("late-1");
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(response).isNotNull();
        assertThat(response.approved()).isTrue();
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(delay);
        assertThat(state("late-1").responseDelivered()).isTrue();
    }

    @Test
    void applyFaultChangesBehaviourAtRuntimeAndResetRestoresNormal() {
        // Healthy first.
        assertThat(authorize(UUID.randomUUID().toString()).approved()).isTrue();

        // Apply error-rate at runtime -> next call errors.
        applyFault(new FaultProfile(PartnerRoute.AUTHORIZE, FaultMode.ERROR_RATE, 0, 0, 1.0, 0, 1, null));
        assertThat(authorizeStatus(UUID.randomUUID().toString())).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        // Reset -> behaviour returns to normal.
        client.post().uri("/control/faults/reset").exchange().expectStatus().isNoContent();
        assertThat(authorize(UUID.randomUUID().toString()).approved()).isTrue();
    }

    @Test
    void errorRateHitsConfiguredProbabilityOverHttpAndIsDeterministicWhenSeeded() {
        int requests = 200;
        double probability = 0.4;

        boolean[] firstRun = runErrorRateBatch(probability, requests);
        // Re-apply the SAME seed and replay the same request-id sequence: outcomes must match exactly.
        boolean[] secondRun = runErrorRateBatch(probability, requests);

        assertThat(secondRun).isEqualTo(firstRun);

        long errors = 0;
        for (boolean errored : firstRun) {
            if (errored) {
                errors++;
            }
        }
        double observed = (double) errors / requests;
        assertThat(observed).isCloseTo(probability, org.assertj.core.data.Offset.offset(0.08));
    }

    /** Apply a seeded error-rate, fire N fresh request ids, and return the per-request error flags. */
    private boolean[] runErrorRateBatch(double probability, int requests) {
        client.post()
                .uri("/control/faults/reset?state=true")
                .exchange()
                .expectStatus()
                .isNoContent();
        applyFault(new FaultProfile(PartnerRoute.AUTHORIZE, FaultMode.ERROR_RATE, 0, 0, probability, 0, 4242, null));

        boolean[] errored = new boolean[requests];
        for (int i = 0; i < requests; i++) {
            errored[i] = authorizeStatus("seeded-" + i) == HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return errored;
    }

    @Test
    void duplicateModeAppliesToCaptureAndReverseRoutesToo() {
        applyFault(new FaultProfile(PartnerRoute.CAPTURE, FaultMode.DUPLICATE, 0, 0, 0.0, 0, 0, null));
        applyFault(new FaultProfile(PartnerRoute.REVERSE, FaultMode.DUPLICATE, 0, 0, 0.0, 0, 0, null));

        CaptureResponse capture = client.post()
                .uri("/partner/capture")
                .body(new CaptureRequest("dup-cap", "AUTH-1", 1000))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(CaptureResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(capture).isNotNull();
        assertThat(capture.duplicate()).isTrue();

        ReverseResponse reverse = client.post()
                .uri("/partner/reverse")
                .body(new ReverseRequest("dup-rev", "AUTH-1"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(ReverseResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(reverse).isNotNull();
        assertThat(reverse.duplicate()).isTrue();
    }

    @Test
    void faultsAreAddressablePerRoute() {
        // Decline only authorize; capture/reverse stay healthy.
        applyFault(new FaultProfile(PartnerRoute.AUTHORIZE, FaultMode.DECLINE, 0, 0, 1.0, 0, 7, "DO_NOT_HONOR"));

        assertThat(authorize("route-auth").approved()).isFalse();

        CaptureResponse capture = client.post()
                .uri("/partner/capture")
                .body(new CaptureRequest("route-cap", "AUTH-1", 1000))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(CaptureResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(capture).isNotNull();
        assertThat(capture.captured()).isTrue();
    }
}
