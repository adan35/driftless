package io.driftless.outbox.internal;

import io.driftless.outbox.internal.persistence.OutboxEventRepository;
import io.driftless.outbox.internal.persistence.OutboxStatus;
import io.driftless.outbox.spi.OutboxReconView;
import io.driftless.outbox.spi.StuckOutboxEvent;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Spec 02 implementation of the read-only {@link OutboxReconView} SPI. It reports the relay's
 * {@code PENDING} backlog past a staleness threshold from the committed {@code outbox_event} rows, so
 * reconciliation can flag a wedged relay without binding to the outbox's internal persistence.
 *
 * <p>Read-only: it observes the backlog, it never drains or mutates it.
 */
@Component
@RequiredArgsConstructor
public class OutboxReconViewImpl implements OutboxReconView {

    private final OutboxEventRepository events;

    @Override
    @Transactional(readOnly = true)
    public long countStuckPending(Instant olderThan) {
        return events.countByStatusAndOccurredAtBefore(OutboxStatus.PENDING, olderThan);
    }

    @Override
    @Transactional(readOnly = true)
    public long countPending() {
        return events.countByStatus(OutboxStatus.PENDING);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StuckOutboxEvent> findStuckPending(Instant olderThan, int limit) {
        return events
                .findByStatusAndOccurredAtBeforeOrderByOccurredAtAsc(OutboxStatus.PENDING, olderThan, Limit.of(limit))
                .stream()
                .map(e -> new StuckOutboxEvent(e.getId(), e.getAggregateType(), e.getEventType(), e.getOccurredAt()))
                .toList();
    }
}
