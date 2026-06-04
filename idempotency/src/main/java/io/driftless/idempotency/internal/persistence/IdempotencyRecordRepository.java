package io.driftless.idempotency.internal.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access to {@code idempotency_record}.
 *
 * <p>The guard reads first and, on the concurrent-duplicate path, relies on the unique constraint
 * surfacing as a {@code DataIntegrityViolationException} from {@code saveAndFlush}. {@link
 * #findForUpdate} takes a row lock so the losing transaction blocks until the winner commits its
 * {@code COMPLETED} result, then reads it — there is never a window that returns an unfinished record.
 */
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecordEntity, IdempotencyRecordId> {

    /**
     * Look up a record by scope and key, taking a pessimistic write lock. Used on the duplicate path:
     * the loser blocks on the winner's uncommitted row and then sees the committed result.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            """
            SELECT r FROM IdempotencyRecordEntity r
            WHERE r.scope = :scope AND r.idempotencyKey = :key
            """)
    Optional<IdempotencyRecordEntity> findForUpdate(@Param("scope") String scope, @Param("key") String key);
}
