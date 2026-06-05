package io.driftless.auth.internal.persistence;

import io.driftless.auth.api.AuthorizationStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data access to {@code authorization} — the saga aggregate keyed by its {@link UUID} id. */
public interface AuthorizationRepository extends JpaRepository<AuthorizationEntity, UUID> {

    /**
     * Load the authorization under a {@code SELECT ... FOR UPDATE} row lock so concurrent transitions
     * (a retry, a recovery sweep, and the partner-leg finalizer) serialize on one row: the second
     * transaction blocks until the first commits, then re-reads the committed status and re-checks the
     * edge — so it can never act on a stale state.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AuthorizationEntity a where a.id = :id")
    Optional<AuthorizationEntity> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Resolve, without a lock, just enough of the aggregate (status, partner reference, amounts) to
     * validate a capture/reverse and address the partner <em>before</em> the bounded partner call —
     * so no {@code FOR UPDATE} row lock is held across the network (review W1). The mutating step
     * re-loads {@link #findByIdForUpdate} and re-checks the live status under the lock.
     */
    @Query("select new io.driftless.auth.internal.persistence.AuthPreview("
            + "a.status, a.partnerRef, a.currency, a.amountMinor, a.capturedAmountMinor) "
            + "from AuthorizationEntity a where a.id = :id")
    Optional<AuthPreview> findPreview(@Param("id") UUID id);

    /** Count authorizations in a given status — e.g. outstanding {@code COMPENSATING} partner obligations. */
    long countByStatus(AuthorizationStatus status);

    /**
     * In-flight authorizations of a given status whose last change is older than {@code threshold} —
     * the recovery sweep's work list (an {@code AUTHORIZING} stuck past the partner timeout, or a
     * {@code COMPENSATING} that has not yet finished). Limited to keep each sweep bounded.
     */
    List<AuthorizationEntity> findByStatusAndUpdatedAtBefore(AuthorizationStatus status, Instant threshold);
}
