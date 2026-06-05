package io.driftless.outbox.spi;

import java.time.Instant;
import java.util.List;

/**
 * Read-only SPI the idempotency/outbox module (Spec 02) publishes for reliability
 * <em>reconciliation</em> (Spec 07): the relay backlog of events still {@code PENDING} past a
 * staleness threshold.
 *
 * <p>Strictly read-only — reconciliation observes the relay's backlog, it never drains it (the relay
 * owns delivery). Defined here on the {@code spi} so the recon module integrates through a published
 * contract rather than binding to the outbox's internal JPA schema.
 */
public interface OutboxReconView {

    /** Count of events still {@code PENDING} that were appended strictly before {@code olderThan}. */
    long countStuckPending(Instant olderThan);

    /**
     * Total count of events still {@code PENDING} (the relay's current queue depth), regardless of
     * age — the gauge the Spec 08 dashboard reads for outbox health. A healthy relay keeps this near
     * zero; a sustained rise signals a wedged or lagging relay before any event becomes "stuck".
     */
    long countPending();

    /**
     * Up to {@code limit} of the oldest stuck {@code PENDING} events (appended before {@code
     * olderThan}), oldest first, to name as offenders.
     */
    List<StuckOutboxEvent> findStuckPending(Instant olderThan, int limit);
}
