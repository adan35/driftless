package io.driftless.partnersim.partner.state;

import io.driftless.partnersim.fault.PartnerRoute;
import java.util.List;

/**
 * Read-only projection of the simulator's own state for one request id, exposed via {@code
 * GET /partner/state/{requestId}} so tests and the demo can inspect what the simulator actually did.
 *
 * @param requestId the inspected request id
 * @param known whether the simulator has any state for this id
 * @param route the route the request targeted, if known
 * @param responseDelivered whether a successful response was delivered to the caller
 * @param sideEffectsPerformed whether any server-side side effect was recorded for this id
 * @param sideEffects the recorded side effects (e.g. work done under {@code FAIL_BEFORE_RESPONSE})
 * @param response the original recorded response, if one was delivered
 */
public record RequestStateView(
        String requestId,
        boolean known,
        PartnerRoute route,
        boolean responseDelivered,
        boolean sideEffectsPerformed,
        List<SideEffect> sideEffects,
        Object response) {

    public static RequestStateView unknown(String requestId) {
        return new RequestStateView(requestId, false, null, false, false, List.of(), null);
    }
}
