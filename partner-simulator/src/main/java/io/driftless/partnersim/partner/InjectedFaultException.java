package io.driftless.partnersim.partner;

import io.driftless.partnersim.fault.FaultMode;

/**
 * Thrown to surface an injected server-side failure as an HTTP 500 to the caller. Carries the {@link
 * FaultMode} that produced it so the error handler and logs can name the exact injected condition.
 *
 * <p>This is a deliberate, controllable misbehaviour — not a programming error.
 */
public class InjectedFaultException extends RuntimeException {

    private final transient FaultMode faultMode;

    public InjectedFaultException(FaultMode faultMode, String message) {
        super(message);
        this.faultMode = faultMode;
    }

    public FaultMode faultMode() {
        return faultMode;
    }
}
