package io.driftless.idempotency.api;

/**
 * The outcome of {@link IdempotencyGuard#execute}: the operation's value plus whether it was served
 * from a prior, already-committed execution rather than freshly run.
 *
 * @param value the operation result — freshly computed on the first call, reconstructed from the
 *     stored response on a replay
 * @param replayed {@code true} when this call returned a previously stored result and produced no new
 *     side effect; {@code false} when the operation ran for the first time
 * @param <T> the operation's result type
 */
public record IdempotentResult<T>(T value, boolean replayed) {}
