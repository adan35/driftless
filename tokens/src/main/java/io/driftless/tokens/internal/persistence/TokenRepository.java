package io.driftless.tokens.internal.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data access to {@code token} — the lifecycle aggregate keyed by its {@link UUID} id. */
public interface TokenRepository extends JpaRepository<TokenEntity, UUID> {

    /**
     * Load the token under a {@code SELECT ... FOR UPDATE} row lock so that concurrent transitions on
     * the same token serialize (Spec 06 review fix C1).
     *
     * <p>Two different transitions on one token take different idempotency keys, so the guard does not
     * serialize them. Loading the row FOR UPDATE inside the guarded supplier makes the second
     * transaction block until the first commits, then re-read the committed status (e.g. {@code
     * DEACTIVATED}) and re-check the edge — so it can never act on a stale {@code from} and resurrect a
     * terminal token or overwrite the first write.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TokenEntity t where t.id = :id")
    Optional<TokenEntity> findByIdForUpdate(@Param("id") UUID id);
}
