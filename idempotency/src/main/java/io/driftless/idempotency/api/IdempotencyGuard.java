package io.driftless.idempotency.api;

import java.util.function.Supplier;

/**
 * Wraps a mutating operation so it runs at most once per idempotency key; a retry returns the stored
 * result and produces no new side effect.
 *
 * <p>This is the system-wide implementation of invariant 3 (idempotency). The guard records the key
 * <em>in the same database transaction</em> as the business write, so the side effect and the
 * idempotency record commit or roll back together. A concurrent duplicate is caught by the unique
 * constraint on the record; the loser reads and returns the winner's stored result. A key reused with
 * a different {@code requestHash} is a conflict, never a replay.
 *
 * <p>The {@code operation} is invoked at most once — only on the first, winning execution for a key.
 * On any replay (a committed prior result, including after a process crash) it is not invoked; the
 * value is reconstructed from the stored response instead.
 */
public interface IdempotencyGuard {

    /**
     * Run {@code operation} exactly once for {@code key}, or replay its stored result.
     *
     * @param key the caller-supplied {@code Idempotency-Key} (opaque, non-blank)
     * @param requestHash a hash of the canonical request body; the same key with a <em>different</em>
     *     hash is an {@link IdempotencyConflict}, not a replay
     * @param operation the side-effecting work, executed at most once per key; it returns a {@link
     *     StoredResult} carrying both the live value and the serialized form to persist
     * @param <T> the operation's result type
     * @return the operation's value with {@code replayed=false} on first execution, or the stored
     *     value with {@code replayed=true} on a retry
     * @throws IdempotencyConflict if {@code key} was first used with a different {@code requestHash}
     */
    <T> IdempotentResult<T> execute(String key, String requestHash, Supplier<StoredResult<T>> operation);
}
