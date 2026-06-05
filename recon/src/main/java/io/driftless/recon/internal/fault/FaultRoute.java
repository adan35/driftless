package io.driftless.recon.internal.fault;

/**
 * The partner business routes a fault can be addressed to, mirroring the partner-simulator's {@code
 * PartnerRoute} on the wire. Declared here so recon keeps no compile dependency on that separate
 * service (integration is HTTP only) — the same pattern the auth module uses for its partner messages.
 */
public enum FaultRoute {
    AUTHORIZE,
    CAPTURE,
    REVERSE
}
