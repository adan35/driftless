package io.driftless.partnersim.partner.state;

import io.driftless.partnersim.fault.PartnerRoute;

/**
 * The stored outcome of a successfully-completed request, keyed by request id, used to replay the
 * original response on retry (idempotency).
 *
 * @param requestId the idempotency key
 * @param route the route that produced the record
 * @param response the original response object to echo on replay
 */
public record RequestRecord(String requestId, PartnerRoute route, Object response) {}
