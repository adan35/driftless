package io.driftless.recon.internal.fault;

/**
 * The partner misbehaviours the harness can inject, mirroring the partner-simulator's {@code
 * FaultMode} on the wire. Declared here so recon keeps no compile dependency on that separate
 * service.
 */
public enum FaultKind {
    NONE,
    LATENCY,
    FAIL_BEFORE_RESPONSE,
    TIMEOUT,
    DECLINE,
    ERROR_RATE,
    DUPLICATE,
    LATE_RESPONSE
}
