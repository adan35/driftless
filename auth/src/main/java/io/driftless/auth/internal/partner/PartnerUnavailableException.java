package io.driftless.auth.internal.partner;

/**
 * Thrown when a partner leg with strict success requirements (capture, or a best-effort reverse the
 * caller chooses to surface) could not be confirmed — a timeout, connection drop, or 5xx. The capture
 * path lets this propagate so its guarded transaction rolls back and writes nothing; the reverse path
 * swallows it (best-effort) since the local hold release/state change must commit regardless.
 */
public class PartnerUnavailableException extends RuntimeException {

    public PartnerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
