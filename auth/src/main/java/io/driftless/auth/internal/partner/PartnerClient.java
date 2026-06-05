package io.driftless.auth.internal.partner;

import io.driftless.auth.internal.partner.PartnerMessages.AuthorizeRequest;
import io.driftless.auth.internal.partner.PartnerMessages.AuthorizeResponse;
import io.driftless.auth.internal.partner.PartnerMessages.CaptureRequest;
import io.driftless.auth.internal.partner.PartnerMessages.CaptureResponse;
import io.driftless.auth.internal.partner.PartnerMessages.ReverseRequest;
import io.driftless.auth.internal.partner.PartnerMessages.ReverseResponse;
import io.driftless.auth.internal.partner.PartnerMessages.SideEffect;
import io.driftless.auth.internal.partner.PartnerMessages.StateView;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The saga's HTTP client for the external partner legs, called under the bounded timeout configured
 * on the injected {@link RestClient}.
 *
 * <p>Failure mapping is the whole point: a timeout, connection drop or 5xx surfaces as a {@link
 * RestClientException}, which {@link #authorize} folds into a {@link PartnerAuthOutcome.Kind#NO_RESPONSE}
 * outcome (so the saga compensates) and which {@link #capture}/{@link #reverse} raise as a typed
 * {@link PartnerUnavailableException}. No PAN is ever logged — only the request id and the log-safe
 * card reference.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PartnerClient {

    private final RestClient partnerRestClient;

    /**
     * Call the partner authorize leg. Never throws for a business outcome: a partner decline is a
     * {@link PartnerAuthOutcome.Kind#DECLINED}; a timeout/5xx/connection failure is a {@link
     * PartnerAuthOutcome.Kind#NO_RESPONSE}, the signal to compensate.
     */
    public PartnerAuthOutcome authorize(
            String requestId, String cardRef, long amountMinor, String currency, String mcc, String merchantId) {
        AuthorizeRequest body = new AuthorizeRequest(requestId, cardRef, amountMinor, currency, mcc, merchantId);
        try {
            AuthorizeResponse response = partnerRestClient
                    .post()
                    .uri("/partner/authorize")
                    .body(body)
                    .retrieve()
                    .body(AuthorizeResponse.class);
            if (response == null) {
                log.warn("partner authorize requestId={} returned no body; treating as no-response", requestId);
                return PartnerAuthOutcome.noResponse();
            }
            if (response.approved()) {
                log.info("partner approved requestId={} partnerRef={}", requestId, response.partnerRef());
                return PartnerAuthOutcome.approved(response.partnerRef());
            }
            log.info("partner declined requestId={} reason={}", requestId, response.declineReason());
            return PartnerAuthOutcome.declined(response.declineReason());
        } catch (RestClientException e) {
            log.warn("partner authorize requestId={} failed ({}); treating as no-response", requestId, e.toString());
            return PartnerAuthOutcome.noResponse();
        }
    }

    /**
     * Call the partner capture leg. The partner is idempotent on {@code requestId}.
     *
     * @throws PartnerUnavailableException on timeout/connection drop/5xx so the caller's guarded
     *     transaction rolls back and nothing is posted
     */
    public void capture(String requestId, String partnerRef, long amountMinor) {
        try {
            CaptureResponse response = partnerRestClient
                    .post()
                    .uri("/partner/capture")
                    .body(new CaptureRequest(requestId, partnerRef, amountMinor))
                    .retrieve()
                    .body(CaptureResponse.class);
            log.info(
                    "partner capture requestId={} partnerRef={} captured={}",
                    requestId,
                    partnerRef,
                    response != null && response.captured());
        } catch (RestClientException e) {
            throw new PartnerUnavailableException("partner capture failed for requestId " + requestId, e);
        }
    }

    /**
     * Call the partner reverse leg (idempotent on {@code requestId}).
     *
     * @throws PartnerUnavailableException on timeout/connection drop/5xx; the compensation loop retries
     */
    public void reverse(String requestId, String partnerRef) {
        try {
            ReverseResponse response = partnerRestClient
                    .post()
                    .uri("/partner/reverse")
                    .body(new ReverseRequest(requestId, partnerRef))
                    .retrieve()
                    .body(ReverseResponse.class);
            log.info(
                    "partner reverse requestId={} partnerRef={} reversed={}",
                    requestId,
                    partnerRef,
                    response != null && response.reversed());
        } catch (RestClientException e) {
            throw new PartnerUnavailableException("partner reverse failed for requestId " + requestId, e);
        }
    }

    /**
     * Resolve, from the partner's own recorded state, what became of an authorize request whose in-line
     * outcome was lost to a crash — the recovery sweep's decision input. Returns {@link
     * PartnerAuthOutcome.Kind#APPROVED}/{@link PartnerAuthOutcome.Kind#DECLINED} when the partner has a
     * recorded response, else {@link PartnerAuthOutcome.Kind#NO_RESPONSE} (unknown, or only a
     * side-effect from a fail-before-response) so the sweep compensates.
     */
    public PartnerAuthOutcome recoverOutcome(String requestId) {
        try {
            StateView view = partnerRestClient
                    .get()
                    .uri("/partner/state/{requestId}", requestId)
                    .retrieve()
                    .body(StateView.class);
            if (view == null || !view.known()) {
                return PartnerAuthOutcome.noResponse();
            }
            AuthorizeResponse response = view.response();
            if (response != null && response.partnerRef() != null) {
                return response.approved()
                        ? PartnerAuthOutcome.approved(response.partnerRef())
                        : PartnerAuthOutcome.declined(response.declineReason());
            }
            return PartnerAuthOutcome.noResponse();
        } catch (RestClientException e) {
            log.warn("partner recover-outcome requestId={} failed ({})", requestId, e.toString());
            return PartnerAuthOutcome.noResponse();
        }
    }

    /**
     * Discover the partnerRef the partner assigned for {@code requestId}, even when the original
     * response was hidden by a timeout or a fail-before-response: it inspects {@code
     * GET /partner/state/{requestId}} for a recorded side effect or response. Returns empty when the
     * partner knows nothing about the request (so no authorization side effect exists to reverse).
     */
    public Optional<String> discoverPartnerRef(String requestId) {
        try {
            StateView view = partnerRestClient
                    .get()
                    .uri("/partner/state/{requestId}", requestId)
                    .retrieve()
                    .body(StateView.class);
            if (view == null || !view.known()) {
                return Optional.empty();
            }
            if (view.response() != null && view.response().partnerRef() != null) {
                return Optional.of(view.response().partnerRef());
            }
            if (view.sideEffects() != null) {
                return view.sideEffects().stream()
                        .map(SideEffect::partnerRef)
                        .filter(ref -> ref != null && !ref.isBlank())
                        .findFirst();
            }
            return Optional.empty();
        } catch (RestClientException e) {
            log.warn("partner state lookup requestId={} failed ({})", requestId, e.toString());
            return Optional.empty();
        }
    }
}
