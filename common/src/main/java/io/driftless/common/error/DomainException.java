package io.driftless.common.error;

/**
 * Base type for all Driftless domain errors.
 *
 * <p>Domain exceptions are unchecked so business code stays free of boilerplate {@code throws}
 * clauses, but they are a distinct, catchable hierarchy that the web layer maps to typed HTTP
 * responses. Throw a subtype that names the violated rule rather than this base directly.
 */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }

    protected DomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
