package io.driftless.partnersim.control;

import io.driftless.partnersim.fault.FaultMode;
import io.driftless.partnersim.fault.FaultProfile;
import io.driftless.partnersim.fault.PartnerRoute;
import io.driftless.partnersim.partner.dto.AuthorizeRequest;
import io.driftless.partnersim.support.SimulatorIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Acceptance for the control-plane endpoints: apply, read, reset, full reset, and input validation. */
class ControlPlaneTest extends SimulatorIntegrationTest {

    private void authorize(String requestId) {
        client.post()
                .uri("/partner/authorize")
                .body(new AuthorizeRequest(requestId, "card", 100, "USD", null, null))
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void getFaultsReportsActiveProfilePerRoute() {
        client.post()
                .uri("/control/faults")
                .body(new FaultProfile(PartnerRoute.CAPTURE, FaultMode.LATENCY, 200, 0, 0.0, 0, 0, null))
                .exchange()
                .expectStatus()
                .isOk();

        client.get()
                .uri("/control/faults")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.CAPTURE.mode")
                .isEqualTo("LATENCY")
                .jsonPath("$.AUTHORIZE.mode")
                .isEqualTo("NONE");
    }

    @Test
    void invalidProfileIsRejectedWith400() {
        // LATENCY with delayMillis=0 violates the profile's @AssertTrue consistency check.
        client.post()
                .uri("/control/faults")
                .body(new FaultProfile(PartnerRoute.AUTHORIZE, FaultMode.LATENCY, 0, 0, 0.0, 0, 0, null))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void resetWithStateFlagClearsRecordedRequestState() {
        authorize("clr-1");
        client.get().uri("/partner/state/clr-1").exchange().expectStatus().isOk();

        client.post()
                .uri("/control/faults/reset?state=true")
                .exchange()
                .expectStatus()
                .isNoContent();

        // State is gone after a full reset.
        client.get().uri("/partner/state/clr-1").exchange().expectStatus().isNotFound();
    }

    @Test
    void plainResetKeepsRecordedStateButClearsFaults() {
        authorize("keep-1");

        client.post().uri("/control/faults/reset").exchange().expectStatus().isNoContent();

        // Default reset clears faults only; recorded state survives for inspection.
        client.get().uri("/partner/state/keep-1").exchange().expectStatus().isOk();
    }

    @Test
    void malformedJsonBodyIsRejectedWith400() {
        client.post()
                .uri("/control/faults")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{ not valid json ")
                .exchange()
                .expectStatus()
                .isBadRequest();
    }
}
