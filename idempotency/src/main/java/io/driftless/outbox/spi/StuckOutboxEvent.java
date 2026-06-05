package io.driftless.outbox.spi;

import java.time.Instant;

/**
 * A stuck transactional-outbox event: one still {@code PENDING} past the staleness threshold, i.e.
 * appended but not yet relayed. A backlog of these is a reliability defect the Spec 07
 * reconciliation job surfaces (the relay is wedged), distinct from monetary drift.
 *
 * @param id the outbox event's monotonic id
 * @param aggregateType the aggregate that emitted it (e.g. {@code authorization})
 * @param eventType the event type
 * @param occurredAt when it was appended (in the caller's transaction)
 */
public record StuckOutboxEvent(long id, String aggregateType, String eventType, Instant occurredAt) {}
