package io.driftless.partnersim.partner.state;

import io.driftless.partnersim.fault.PartnerRoute;

/**
 * A record of work the simulator performed server-side for a request. This exists so a test can prove
 * that {@code FAIL_BEFORE_RESPONSE} actually did the work (recorded a side effect) even though the
 * caller received no successful response — the canonical drift trigger.
 *
 * @param route the route that produced the side effect
 * @param partnerRef the partner reference assigned/affected by the work
 * @param delivered whether a successful response was delivered to the caller for this work; {@code
 *     false} marks a side effect performed without the caller learning of it
 * @param epochMillis simulator wall-clock time the side effect was recorded
 */
public record SideEffect(PartnerRoute route, String partnerRef, boolean delivered, long epochMillis) {}
