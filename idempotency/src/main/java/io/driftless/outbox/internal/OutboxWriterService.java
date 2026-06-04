package io.driftless.outbox.internal;

import io.driftless.outbox.api.OutboxEvent;
import io.driftless.outbox.api.OutboxWriter;
import io.driftless.outbox.internal.persistence.OutboxEventEntity;
import io.driftless.outbox.internal.persistence.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * DB-backed {@link OutboxWriter}: appends the event row inside the caller's transaction so it commits
 * or rolls back atomically with the state change.
 *
 * <p>{@link Propagation#MANDATORY} enforces the contract — calling {@code append} outside a
 * transaction is a programming error and fails fast rather than silently writing a non-atomic event.
 * The row is left {@code PENDING} for the relay to deliver.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxWriterService implements OutboxWriter {

    private final OutboxEventRepository events;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(OutboxEvent event) {
        OutboxEventEntity row = new OutboxEventEntity(
                event.aggregateType(), event.aggregateId(), event.eventType(), event.payloadJson(), event.occurredAt());
        OutboxEventEntity saved = events.save(row);
        log.debug(
                "outbox append aggregateType={} aggregateId={} eventType={} occurredAt={}",
                saved.getAggregateType(),
                saved.getAggregateId(),
                saved.getEventType(),
                saved.getOccurredAt());
    }
}
