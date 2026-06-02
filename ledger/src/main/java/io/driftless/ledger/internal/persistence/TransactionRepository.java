package io.driftless.ledger.internal.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to the {@code transaction} table and its legs. */
public interface TransactionRepository extends JpaRepository<TransactionEntity, UUID> {

    /**
     * Look up a transaction by its idempotency key — the read-side of the replay guard. {@code
     * entries} is fetched eagerly via an entity graph so the mapping to the api {@code Transaction}
     * does not trigger an N+1 load.
     */
    @EntityGraph(attributePaths = "entries")
    Optional<TransactionEntity> findByIdempotencyKey(String idempotencyKey);

    /** Read a transaction with its legs in one query (no N+1) for {@code findTransaction}. */
    @EntityGraph(attributePaths = "entries")
    Optional<TransactionEntity> findWithEntriesById(UUID id);
}
