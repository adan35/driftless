package io.driftless.tokens.internal.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data access to the append-only {@code token_status_history} audit trail.
 *
 * <p>{@link #countByTokenId} returns the number of transitions a token has undergone so far; the
 * keyless transition path folds it into the derived idempotency key so that a re-applied transition
 * of the <em>same</em> kind (e.g. a second {@code suspend} after a {@code resume}) gets a distinct
 * key from the first and is therefore a genuine new transition rather than a false replay.
 */
public interface TokenStatusHistoryRepository extends JpaRepository<TokenStatusHistoryEntity, UUID> {

    /** Number of recorded transitions for a token (used to derive a per-occurrence idempotency key). */
    long countByTokenId(UUID tokenId);
}
