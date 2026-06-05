package io.driftless.outbox.internal.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access to {@code outbox_event}.
 *
 * <p>The relay claims work with {@link #lockNextPending}: the oldest {@code PENDING} rows in id order
 * under {@code FOR UPDATE SKIP LOCKED}, so concurrent relay instances never block on or double-claim
 * the same rows, and per-aggregate ordering is preserved because global id order is a superset of
 * each aggregate's order. The locked rows stay locked for the relay transaction's lifetime, covering
 * publish-then-mark.
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, Long> {

    /**
     * Lock and return up to {@code limit} of the oldest pending events, skipping rows another relay
     * instance already holds. Ordered by the monotonic id so delivery follows append order.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @org.springframework.data.jpa.repository.Query(
            """
            SELECT e FROM OutboxEventEntity e
            WHERE e.status = io.driftless.outbox.internal.persistence.OutboxStatus.PENDING
            ORDER BY e.id ASC
            """)
    List<OutboxEventEntity> lockNextPending(Limit limit);

    /** Count of events still awaiting delivery — used by tests and health checks. */
    long countByStatus(@Param("status") OutboxStatus status);

    /**
     * Count of events in a status appended strictly before {@code threshold} — the stuck-relay
     * backlog the Spec 07 reconciliation job flags when {@code status} is {@code PENDING}.
     */
    long countByStatusAndOccurredAtBefore(OutboxStatus status, Instant threshold);

    /** Up to {@code limit} events in a status appended before {@code threshold}, oldest first. */
    List<OutboxEventEntity> findByStatusAndOccurredAtBeforeOrderByOccurredAtAsc(
            OutboxStatus status, Instant threshold, Limit limit);
}
