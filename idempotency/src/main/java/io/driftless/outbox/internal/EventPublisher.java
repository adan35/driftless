package io.driftless.outbox.internal;

/**
 * The pluggable seam between the outbox relay and the outside world.
 *
 * <p>For the MVP this is an in-process publisher; a real message broker (Kafka, SNS, …) slots in
 * behind the same interface without touching the relay or the {@code outbox.api} contract. Delivery
 * is at-least-once: the relay publishes, then marks the row {@code PUBLISHED} in a separate step, so
 * a crash in between re-presents the event on restart. Implementations are therefore expected to be
 * safe to call more than once for the same event id, or to front an idempotent consumer.
 */
public interface EventPublisher {

    /**
     * Publish one event. Must complete (or throw) before the relay marks the row published; throwing
     * leaves the row {@code PENDING} for a later retry.
     *
     * @param event the event to deliver, carrying the persisted id consumers dedup on
     */
    void publish(PublishedOutboxEvent event);
}
