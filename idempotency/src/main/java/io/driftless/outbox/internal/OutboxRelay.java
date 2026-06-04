package io.driftless.outbox.internal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The scheduled outbox relay: it sweeps pending events and drives them to the {@link EventPublisher}
 * exactly once (at-least-once delivery + idempotent consumers = effectively once).
 *
 * <p>Each sweep drains successive transactional batches via {@link OutboxBatchPublisher} until the
 * queue empties or a per-sweep batch cap is reached, so a backlog is worked down promptly without a
 * single sweep running unbounded. Crash-safety and ordering live in the batch publisher; this class
 * only schedules and bounds the work, and is deliberately thin so a real broker can replace the
 * {@link EventPublisher} without touching it.
 */
@Slf4j
@Component
public class OutboxRelay {

    private final OutboxBatchPublisher batchPublisher;
    private final OutboxRelayProperties properties;

    public OutboxRelay(OutboxBatchPublisher batchPublisher, OutboxRelayProperties properties) {
        this.batchPublisher = batchPublisher;
        this.properties = properties;
    }

    /** Fixed-delay sweep; the delay is configurable via {@code driftless.outbox.relay.poll-delay-millis}. */
    @Scheduled(fixedDelayString = "${driftless.outbox.relay.poll-delay-millis:500}")
    public void sweep() {
        relayPending();
    }

    /**
     * Drain pending events in batches until the queue is empty or the per-sweep cap is hit. Exposed
     * (package-visible) so tests can trigger a deterministic drain without waiting on the scheduler.
     *
     * @return the total number of events published across the batches of this sweep
     */
    int relayPending() {
        int published = 0;
        for (int batch = 0; batch < properties.maxBatchesPerSweep(); batch++) {
            int count = batchPublisher.publishBatch(properties.batchSize());
            published += count;
            if (count < properties.batchSize()) {
                break;
            }
        }
        if (published > 0) {
            log.info("outbox relay published {} event(s) this sweep", published);
        }
        return published;
    }
}
