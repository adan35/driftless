package io.driftless.partnersim.partner;

import static org.assertj.core.api.Assertions.assertThat;

import io.driftless.partnersim.partner.dto.AuthorizeRequest;
import io.driftless.partnersim.partner.dto.AuthorizeResponse;
import io.driftless.partnersim.partner.dto.CaptureRequest;
import io.driftless.partnersim.partner.dto.CaptureResponse;
import io.driftless.partnersim.partner.dto.ReverseRequest;
import io.driftless.partnersim.partner.dto.ReverseResponse;
import io.driftless.partnersim.partner.state.RequestStateView;
import io.driftless.partnersim.support.SimulatorIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Acceptance: with no faults configured, {@code authorize}/{@code capture}/{@code reverse} behave
 * normally and are idempotent on request id.
 */
class PartnerHappyPathTest extends SimulatorIntegrationTest {

    private AuthorizeResponse postAuthorize(AuthorizeRequest request) {
        return client.post()
                .uri("/partner/authorize")
                .body(request)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(AuthorizeResponse.class)
                .returnResult()
                .getResponseBody();
    }

    @Test
    void authorizeApprovesAndIsIdempotent() {
        AuthorizeRequest request = new AuthorizeRequest("auth-1", "card-ref-1", 1500, "USD", "5411", "merchant-1");

        AuthorizeResponse first = postAuthorize(request);
        AuthorizeResponse replay = postAuthorize(request);

        assertThat(first).isNotNull();
        assertThat(first.approved()).isTrue();
        assertThat(first.requestId()).isEqualTo("auth-1");
        assertThat(first.partnerRef()).isNotBlank();
        assertThat(first.declineReason()).isNull();
        // Idempotent: the replay echoes the original partner ref exactly.
        assertThat(replay).isEqualTo(first);
    }

    @Test
    void captureConfirmsAndIsIdempotent() {
        CaptureRequest request = new CaptureRequest("cap-1", "AUTH-xyz", 1500);

        CaptureResponse first = client.post()
                .uri("/partner/capture")
                .body(request)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(CaptureResponse.class)
                .returnResult()
                .getResponseBody();
        CaptureResponse replay = client.post()
                .uri("/partner/capture")
                .body(request)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(CaptureResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(first).isNotNull();
        assertThat(first.captured()).isTrue();
        assertThat(first.partnerRef()).isEqualTo("AUTH-xyz");
        assertThat(replay).isEqualTo(first);
    }

    @Test
    void reverseConfirmsAndIsIdempotent() {
        ReverseRequest request = new ReverseRequest("rev-1", "AUTH-xyz");

        ReverseResponse first = client.post()
                .uri("/partner/reverse")
                .body(request)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(ReverseResponse.class)
                .returnResult()
                .getResponseBody();
        ReverseResponse replay = client.post()
                .uri("/partner/reverse")
                .body(request)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(ReverseResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(first).isNotNull();
        assertThat(first.reversed()).isTrue();
        assertThat(first.partnerRef()).isEqualTo("AUTH-xyz");
        assertThat(replay).isEqualTo(first);
    }

    @Test
    void stateEndpointReportsDeliveredWorkForHappyPath() {
        postAuthorize(new AuthorizeRequest("auth-state", "card-ref-1", 1500, "USD", null, null));

        RequestStateView view = client.get()
                .uri("/partner/state/auth-state")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(RequestStateView.class)
                .returnResult()
                .getResponseBody();

        assertThat(view).isNotNull();
        assertThat(view.known()).isTrue();
        assertThat(view.responseDelivered()).isTrue();
        assertThat(view.sideEffectsPerformed()).isTrue();
        assertThat(view.sideEffects()).hasSize(1);
        assertThat(view.sideEffects().getFirst().delivered()).isTrue();
    }

    @Test
    void stateEndpointReturns404ForUnknownRequestId() {
        client.get().uri("/partner/state/never-seen").exchange().expectStatus().isNotFound();
    }

    @Test
    void missingRequiredFieldIsRejectedWith400() {
        AuthorizeRequest invalid = new AuthorizeRequest("", "card", 100, "USD", null, null);

        client.post()
                .uri("/partner/authorize")
                .body(invalid)
                .exchange()
                .expectStatus()
                .isBadRequest();
    }
}
