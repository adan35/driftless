package io.driftless.tokens.web;

/**
 * Raised when a mutating token endpoint is called without the required {@code Idempotency-Key}
 * header. Mapped to {@code 400 Bad Request} by {@link TokenExceptionHandler}.
 */
public class MissingIdempotencyKeyException extends RuntimeException {

    public MissingIdempotencyKeyException(String header) {
        super("Missing required header '%s'".formatted(header));
    }
}
