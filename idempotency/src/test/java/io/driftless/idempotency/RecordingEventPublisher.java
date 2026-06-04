package io.driftless.idempotency;

import io.driftless.outbox.internal.EventPublisher;
import io.driftless.outbox.internal.PublishedOutboxEvent;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test double for the relay's publisher seam that models an <em>idempotent consumer keyed by event
 * id</em>.
 *
 * <p>It records two streams: {@link #deliveryAttempts()} — every id the relay handed it, including
 * at-least-once redeliveries — and {@link #consumed()} — the de-duplicated view a real idempotent
 * consumer would observe (each id applied at most once). The two streams together let a test assert
 * "delivered at least once, observed exactly once".
 *
 * <p>{@link #failOnceOn(long)} simulates a crash <em>after</em> delivery but before the relay marks
 * the row published: the consumer records the id (so it is observed), then the publish throws,
 * rolling the relay batch back so the row stays {@code PENDING} and is re-presented on the next
 * sweep — where the dedup absorbs it.
 */
class RecordingEventPublisher implements EventPublisher {

    private final List<Long> deliveryAttempts = new CopyOnWriteArrayList<>();
    private final List<Long> consumed = new CopyOnWriteArrayList<>();
    private final Set<Long> consumedIds = ConcurrentHashMap.newKeySet();
    private volatile long failOnceId = Long.MIN_VALUE;

    @Override
    public synchronized void publish(PublishedOutboxEvent event) {
        deliveryAttempts.add(event.id());
        // Idempotent consumer: apply the effect at most once per event id.
        if (consumedIds.add(event.id())) {
            consumed.add(event.id());
        }
        if (event.id() == failOnceId) {
            failOnceId = Long.MIN_VALUE;
            throw new IllegalStateException(
                    "simulated crash after delivering event " + event.id() + " but before mark-published");
        }
    }

    /** Arm a one-shot failure for the given event id, simulating a crash post-deliver / pre-mark. */
    void failOnceOn(long eventId) {
        this.failOnceId = eventId;
    }

    /** Clear all recorded state so each test observes only its own events. */
    synchronized void reset() {
        deliveryAttempts.clear();
        consumed.clear();
        consumedIds.clear();
        failOnceId = Long.MIN_VALUE;
    }

    /** Every id handed to the publisher, in order, including redeliveries (the at-least-once stream). */
    List<Long> deliveryAttempts() {
        return List.copyOf(deliveryAttempts);
    }

    /** The de-duplicated ids an idempotent consumer observed, in first-seen order. */
    List<Long> consumed() {
        return List.copyOf(consumed);
    }
}
