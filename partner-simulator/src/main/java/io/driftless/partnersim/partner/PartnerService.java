package io.driftless.partnersim.partner;

import io.driftless.partnersim.fault.FaultDecision;
import io.driftless.partnersim.fault.FaultMode;
import io.driftless.partnersim.fault.FaultRegistry;
import io.driftless.partnersim.fault.PartnerRoute;
import io.driftless.partnersim.partner.dto.AuthorizeRequest;
import io.driftless.partnersim.partner.dto.AuthorizeResponse;
import io.driftless.partnersim.partner.dto.CaptureRequest;
import io.driftless.partnersim.partner.dto.CaptureResponse;
import io.driftless.partnersim.partner.dto.ReverseRequest;
import io.driftless.partnersim.partner.dto.ReverseResponse;
import io.driftless.partnersim.partner.state.PartnerStateStore;
import io.driftless.partnersim.partner.state.RequestStateView;
import io.driftless.partnersim.partner.state.SideEffect;
import java.time.Clock;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The partner business logic behind the three external legs ({@code authorize} / {@code capture} /
 * {@code reverse}). Each call is idempotent on its request id and applies whatever {@link
 * FaultDecision} the {@link FaultRegistry} resolves for the route.
 *
 * <p>This service holds <strong>no money or ledger semantics</strong>: amounts and currencies are
 * opaque pass-through fields. Its only job is to behave — or misbehave — like a real network partner.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartnerService {

    private final FaultRegistry faultRegistry;
    private final PartnerStateStore stateStore;
    private final Clock clock;

    public AuthorizeResponse authorize(AuthorizeRequest request) {
        return execute(
                PartnerRoute.AUTHORIZE,
                request.requestId(),
                AuthorizeResponse.class,
                () -> "AUTH-" + UUID.randomUUID(),
                (partnerRef, decision) -> decision.declineReasonOpt()
                        .map(reason -> AuthorizeResponse.declined(request.requestId(), partnerRef, reason))
                        .orElseGet(() -> AuthorizeResponse.approved(request.requestId(), partnerRef)),
                AuthorizeResponse::asDuplicate);
    }

    public CaptureResponse capture(CaptureRequest request) {
        return execute(
                PartnerRoute.CAPTURE,
                request.requestId(),
                CaptureResponse.class,
                request::partnerRef,
                (partnerRef, decision) -> CaptureResponse.captured(request.requestId(), partnerRef),
                CaptureResponse::asDuplicate);
    }

    public ReverseResponse reverse(ReverseRequest request) {
        return execute(
                PartnerRoute.REVERSE,
                request.requestId(),
                ReverseResponse.class,
                request::partnerRef,
                (partnerRef, decision) -> ReverseResponse.reversed(request.requestId(), partnerRef),
                ReverseResponse::asDuplicate);
    }

    /** Inspect the simulator's own state for a request id. */
    public RequestStateView state(String requestId) {
        return stateStore.view(requestId);
    }

    /**
     * Shared request lifecycle for all three legs: idempotency replay → fault decision → delay → fault
     * application → work + record. The route-specific bits are supplied as functions so no money logic
     * leaks in and the three legs stay consistent.
     *
     * @param route the partner route being served
     * @param requestId the caller's idempotency key
     * @param responseType the response type, for a safe replay cast
     * @param partnerRefSupplier produces the partner reference (new for authorize, echoed otherwise)
     * @param responseFactory builds the response from the partner ref and decision; for authorize it
     *     honours a firing {@code DECLINE}, for capture/reverse a decline is treated as success since
     *     those legs have no decline semantics
     * @param markDuplicate produces the duplicate-flagged copy for the {@code DUPLICATE} fault
     */
    private <T> T execute(
            PartnerRoute route,
            String requestId,
            Class<T> responseType,
            Supplier<String> partnerRefSupplier,
            BiFunction<String, FaultDecision, T> responseFactory,
            Function<T, T> markDuplicate) {

        var replay = stateStore.findRecord(requestId);
        if (replay.isPresent()) {
            T original = responseType.cast(replay.get().response());
            log.info("idempotent replay route={} requestId={}", route, requestId);
            return original;
        }

        FaultDecision decision = faultRegistry.decide(route);
        log.info("serving route={} requestId={} mode={}", route, requestId, decision.mode());

        sleepIfNeeded(decision);

        String partnerRef = partnerRefSupplier.get();
        return switch (decision.mode()) {
            case ERROR_RATE -> throw new InjectedFaultException(
                    FaultMode.ERROR_RATE, "injected error for route %s requestId %s".formatted(route, requestId));
            case FAIL_BEFORE_RESPONSE -> {
                // Do the work and record it, then fail WITHOUT a successful response: the drift trigger.
                stateStore.recordSideEffect(requestId, new SideEffect(route, partnerRef, false, clock.millis()));
                log.warn(
                        "fail-before-response: work performed but no success returned route={} requestId={} partnerRef={}",
                        route,
                        requestId,
                        partnerRef);
                throw new InjectedFaultException(
                        FaultMode.FAIL_BEFORE_RESPONSE,
                        "side effect performed but response suppressed for route %s requestId %s"
                                .formatted(route, requestId));
            }
            case DUPLICATE -> {
                T response = responseFactory.apply(partnerRef, decision);
                completeNormally(route, requestId, partnerRef, response);
                T duplicate = markDuplicate.apply(response);
                log.warn(
                        "duplicate response emitted route={} requestId={} partnerRef={}", route, requestId, partnerRef);
                yield duplicate;
            }
                // NONE / LATENCY / TIMEOUT / LATE_RESPONSE and DECLINE all complete via the factory,
                // which already encodes approve vs. decline through the decision's reason.
            default -> {
                T response = responseFactory.apply(partnerRef, decision);
                completeNormally(route, requestId, partnerRef, response);
                yield response;
            }
        };
    }

    private <T> void completeNormally(PartnerRoute route, String requestId, String partnerRef, T response) {
        stateStore.recordSideEffect(requestId, new SideEffect(route, partnerRef, true, clock.millis()));
        stateStore.recordCompletion(requestId, route, response);
    }

    /** Sleep for the resolved delay of any delay-bearing mode; interruption ends the wait early. */
    private void sleepIfNeeded(FaultDecision decision) {
        long delay = decision.delayMillis();
        if (delay <= 0) {
            return;
        }
        log.info("injecting delay mode={} delayMs={}", decision.mode(), delay);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("delay interrupted mode={} delayMs={}", decision.mode(), delay);
        }
    }
}
