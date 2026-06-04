package io.driftless.outbox.api;

/**
 * Appends a domain event to the transactional outbox.
 *
 * <p>{@link #append} must be called <em>within the caller's database transaction</em>, alongside the
 * state change the event describes. The event row therefore commits or rolls back atomically with
 * that state change: if the business write rolls back, no event is left behind; if it commits, the
 * event is guaranteed to be relayed. A separate relay polls and publishes appended events.
 */
public interface OutboxWriter {

    /**
     * Append {@code event} to the outbox as {@code PENDING}, in the caller's current transaction.
     *
     * @param event the event to publish exactly once after the surrounding transaction commits
     */
    void append(OutboxEvent event);
}
