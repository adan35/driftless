package io.driftless.partnersim.fault;

/**
 * The partner business routes a fault can be addressed to. Faults are configured per route so a test
 * can, for example, make {@code capture} time out while {@code authorize} stays healthy.
 */
public enum PartnerRoute {
    AUTHORIZE,
    CAPTURE,
    REVERSE
}
