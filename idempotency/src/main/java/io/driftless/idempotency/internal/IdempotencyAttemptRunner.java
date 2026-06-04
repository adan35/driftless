package io.driftless.idempotency.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.driftless.idempotency.api.IdempotencyConflict;
import io.driftless.idempotency.api.IdempotentResult;
import io.driftless.idempotency.api.StoredResult;
import io.driftless.idempotency.internal.persistence.IdempotencyRecordEntity;
import io.driftless.idempotency.internal.persistence.IdempotencyRecordRepository;
import io.driftless.idempotency.internal.persistence.IdempotencyStatus;
import java.time.Clock;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs a single transactional attempt of a guarded operation: read-or-claim the idempotency record,
 * run the operation at most once, and complete the record — all in one transaction so the side
 * effect and its idempotency record commit or roll back together.
 *
 * <p>This is a separate bean from {@link IdempotencyGuardService} so the {@code @Transactional}
 * boundary is a real proxy boundary (a self-call inside the service would bypass the proxy). The
 * service owns the retry loop; this class owns one transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class IdempotencyAttemptRunner {

    private final IdempotencyRecordRepository records;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * One transaction: replay a committed result, run-and-record a fresh one, or signal a concurrent
     * claim to retry.
     *
     * @throws IdempotencyConflict if the key was first used with a different request hash
     * @throws ConcurrentClaimException if another transaction is claiming the same key (retryable)
     */
    @Transactional
    <T> IdempotentResult<T> attempt(String scope, String key, String requestHash, Supplier<StoredResult<T>> operation) {

        Optional<IdempotencyRecordEntity> existing = records.findForUpdate(scope, key);
        if (existing.isPresent()) {
            return replayExisting(existing.get(), key, requestHash);
        }

        IdempotencyRecordEntity claim = new IdempotencyRecordEntity(scope, key, requestHash, clock.instant());
        try {
            records.saveAndFlush(claim);
        } catch (DataIntegrityViolationException race) {
            // A concurrent caller committed the same (scope, key) between our read and insert. Roll
            // back and let the service retry; the next pre-read will see the winner's stored result.
            log.debug("idempotency claim lost race scope={} key={}; will retry to replay", scope, key);
            throw new ConcurrentClaimException("concurrent claim for key " + key, race);
        }

        StoredResult<T> stored = operation.get();
        claim.complete(stored.responseJson(), stored.valueType().getName(), clock.instant());
        records.saveAndFlush(claim);
        log.info("idempotency miss scope={} key={} executed and recorded result", scope, key);
        return new IdempotentResult<>(stored.value(), false);
    }

    private <T> IdempotentResult<T> replayExisting(IdempotencyRecordEntity record, String key, String requestHash) {
        if (!record.getRequestHash().equals(requestHash)) {
            log.warn(
                    "idempotency conflict scope={} key={} storedHash={} replayHash={}",
                    record.getScope(),
                    key,
                    record.getRequestHash(),
                    requestHash);
            throw new IdempotencyConflict(key, record.getRequestHash(), requestHash);
        }
        if (record.getStatus() != IdempotencyStatus.COMPLETED) {
            // A committed record is always COMPLETED in the single-transaction model (the only commit
            // path also completes it). A committed IN_PROGRESS would be a partially applied write; do
            // not fabricate a result — retry so a concurrent completer can win, surfacing real bugs.
            log.warn("idempotency record scope={} key={} committed as IN_PROGRESS; retrying", record.getScope(), key);
            throw new ConcurrentClaimException("record not yet completed for key " + key, null);
        }
        T value = deserialize(record);
        log.info("idempotency replay scope={} key={} returning stored result", record.getScope(), key);
        return new IdempotentResult<>(value, true);
    }

    @SuppressWarnings("unchecked")
    private <T> T deserialize(IdempotencyRecordEntity record) {
        try {
            Class<T> type = (Class<T>) Class.forName(record.getResponseType());
            return objectMapper.readValue(record.getResponseBlob(), type);
        } catch (ReflectiveOperationException
                | RuntimeException
                | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(
                    "failed to deserialize stored idempotent result for key " + record.getIdempotencyKey(), e);
        }
    }
}
