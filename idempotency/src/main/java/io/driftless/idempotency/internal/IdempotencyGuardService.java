package io.driftless.idempotency.internal;

import io.driftless.idempotency.api.IdempotencyGuard;
import io.driftless.idempotency.api.IdempotentResult;
import io.driftless.idempotency.api.StoredResult;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * DB-backed {@link IdempotencyGuard}: the system-wide implementation of invariant 3.
 *
 * <p>Each call delegates to a single transactional {@link IdempotencyAttemptRunner#attempt attempt}.
 * The happy path (fresh key, or a replay of a committed result) succeeds in one attempt. The only
 * retry is the genuine concurrent-claim race: two callers with the same key race to insert; the
 * unique constraint lets exactly one win and run the operation, and the loser's attempt rolls back
 * and retries, where it now reads and replays the winner's committed result. A reused key with a
 * different request hash is an {@link io.driftless.idempotency.api.IdempotencyConflict}, surfaced
 * unchanged.
 *
 * <p>The guarded operation runs at most once: it executes only on the winning attempt and never on a
 * replay, so a retried request — including one issued after a crash that committed the original —
 * produces no new side effect.
 */
@Slf4j
@Service
public class IdempotencyGuardService implements IdempotencyGuard {

    /**
     * Default scope for keys not otherwise partitioned by endpoint. Spec 03 can introduce
     * per-endpoint scopes; the storage and unique constraint are already keyed by {@code (scope,
     * key)}, so this is the single-scope default until then.
     */
    static final String DEFAULT_SCOPE = "default";

    /**
     * A claim race resolves in one retry (the winner has committed by the time the loser's blocked
     * insert fails). A small ceiling tolerates pathological interleavings without ever looping
     * unbounded.
     */
    private static final int MAX_ATTEMPTS = 5;

    private final IdempotencyAttemptRunner runner;

    public IdempotencyGuardService(IdempotencyAttemptRunner runner) {
        this.runner = runner;
    }

    @Override
    public <T> IdempotentResult<T> execute(String key, String requestHash, Supplier<StoredResult<T>> operation) {
        requireText(key, "key");
        requireText(requestHash, "requestHash");
        ConcurrentClaimException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return runner.attempt(DEFAULT_SCOPE, key, requestHash, operation);
            } catch (ConcurrentClaimException retryable) {
                last = retryable;
                log.debug("idempotency attempt {} for key={} hit a concurrent claim; retrying", attempt, key);
            }
        }
        throw new IllegalStateException(
                "idempotency key " + key + " did not resolve after " + MAX_ATTEMPTS + " attempts", last);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
