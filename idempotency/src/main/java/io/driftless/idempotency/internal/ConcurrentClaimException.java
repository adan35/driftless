package io.driftless.idempotency.internal;

/**
 * Internal, retryable signal: a concurrent caller won the race to claim the same idempotency key, so
 * this attempt's transaction must roll back and retry. On retry the pre-read sees the winner's now
 * committed result and returns it as a replay.
 *
 * <p>Package-private and never escapes the guard — {@link IdempotencyGuardService} catches it and
 * loops. It carries no business meaning and is not part of the public contract.
 */
final class ConcurrentClaimException extends RuntimeException {

    ConcurrentClaimException(String message, Throwable cause) {
        super(message, cause);
    }
}
