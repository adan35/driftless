package io.driftless.outbox.internal;

import lombok.extern.slf4j.Slf4j;

/**
 * The default in-process {@link EventPublisher} for the MVP: it logs each delivered event.
 *
 * <p>It is the swappable seam's placeholder — registered only when no other {@link EventPublisher}
 * bean is present (see {@code OutboxAutoConfiguration}), so the {@code app} module or a real broker
 * adapter cleanly replaces it. It performs no external I/O, keeping the relay's behaviour observable
 * in the MVP and in tests that do not supply their own publisher.
 */
@Slf4j
public class LoggingEventPublisher implements EventPublisher {

    @Override
    public void publish(PublishedOutboxEvent event) {
        log.info(
                "outbox publish id={} aggregateType={} aggregateId={} eventType={}",
                event.id(),
                event.aggregateType(),
                event.aggregateId(),
                event.eventType());
    }
}
