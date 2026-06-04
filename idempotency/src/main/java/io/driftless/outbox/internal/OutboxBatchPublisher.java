package io.driftless.outbox.internal;

import io.driftless.outbox.internal.persistence.OutboxEventEntity;
import io.driftless.outbox.internal.persistence.OutboxEventRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes one transactional batch of pending outbox events.
 *
 * <p>In a single transaction it locks the oldest pending rows ({@code FOR UPDATE SKIP LOCKED}, id
 * order), then for each — in that order — counts an attempt, hands it to the {@link EventPublisher},
 * and marks it published. Because publish-then-mark live in the same transaction, the design is
 * crash-safe: a process death before commit rolls the batch back, leaving every row {@code PENDING}
 * to be re-presented on restart. An idempotent consumer keyed by the event id absorbs that
 * at-least-once redelivery with no observable duplicate.
 *
 * <p>Ordering is preserved: rows are processed strictly in id order, and a publish failure aborts the
 * whole batch (rollback) so a later event is never delivered ahead of an earlier one that still needs
 * to land. The batch is a separate bean from {@link OutboxRelay} so {@code @Transactional} is a real
 * proxy boundary the scheduled sweep crosses per batch.
 */
@Component
class OutboxBatchPublisher {

    private final OutboxEventRepository events;
    private final EventPublisher publisher;
    private final Clock clock;

    OutboxBatchPublisher(OutboxEventRepository events, EventPublisher publisher, Clock clock) {
        this.events = events;
        this.publisher = publisher;
        this.clock = clock;
    }

    /**
     * Claim and publish up to {@code batchSize} pending events in id order.
     *
     * @return the number of events published in this batch; {@code 0} means the queue is drained
     */
    @Transactional
    int publishBatch(int batchSize) {
        List<OutboxEventEntity> batch = events.lockNextPending(Limit.of(batchSize));
        for (OutboxEventEntity event : batch) {
            event.recordAttempt();
            publisher.publish(new PublishedOutboxEvent(
                    event.getId(),
                    event.getAggregateType(),
                    event.getAggregateId(),
                    event.getEventType(),
                    event.getPayloadJson(),
                    event.getOccurredAt()));
            event.markPublished(clock.instant());
        }
        return batch.size();
    }
}
