package io.driftless.outbox.internal;

import io.driftless.outbox.internal.persistence.OutboxEventEntity;
import io.driftless.outbox.internal.persistence.OutboxEventRepository;
import io.driftless.outbox.spi.OutboxPublication;
import io.driftless.outbox.spi.OutboxPublicationListener;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(OutboxBatchPublisher.class);

    private final OutboxEventRepository events;
    private final EventPublisher publisher;
    private final OutboxPublicationListener publicationListener;
    private final Clock clock;

    OutboxBatchPublisher(
            OutboxEventRepository events,
            EventPublisher publisher,
            OutboxPublicationListener publicationListener,
            Clock clock) {
        this.events = events;
        this.publisher = publisher;
        this.publicationListener = publicationListener;
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
            // Fan the same delivery out to the published observation seam (Spec 08), in id order and in
            // this transaction. The listener dedups on the event id, so a crash-driven redelivery never
            // double-counts. A no-op listener is wired by default, so this is a single unconditional call.
            // The observation seam must never be able to break delivery: a misbehaving or future listener
            // that throws is isolated per event — logged and swallowed — so the relay batch still commits
            // and the event is marked published. Delivery integrity does not depend on a metrics listener.
            try {
                publicationListener.onPublished(new OutboxPublication(
                        event.getId(),
                        event.getAggregateType(),
                        event.getAggregateId(),
                        event.getEventType(),
                        event.getPayloadJson(),
                        event.getOccurredAt()));
            } catch (RuntimeException ex) {
                log.warn(
                        "outbox publication listener failed for event {} ({}/{}) — continuing delivery",
                        event.getId(),
                        event.getAggregateType(),
                        event.getEventType(),
                        ex);
            }
            event.markPublished(clock.instant());
        }
        return batch.size();
    }
}
