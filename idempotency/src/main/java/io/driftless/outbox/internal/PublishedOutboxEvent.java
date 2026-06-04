package io.driftless.outbox.internal;

import java.time.Instant;

/**
 * The immutable view of a stored outbox row handed to an {@link EventPublisher}.
 *
 * <p>The {@code id} is the persisted, monotonic event id — the stable, unique key an idempotent
 * consumer dedups on. Because delivery is at-least-once, a consumer keyed by {@code id} sees no
 * observable duplicate even if the relay re-presents an event after a crash between publish and
 * mark-published.
 *
 * @param id the persisted outbox event id; the consumer's dedup key
 * @param aggregateType the kind of aggregate the event is about
 * @param aggregateId the id of the specific aggregate instance
 * @param eventType the event name
 * @param payloadJson the event payload as JSON
 * @param occurredAt business time the event occurred
 */
public record PublishedOutboxEvent(
        long id, String aggregateType, String aggregateId, String eventType, String payloadJson, Instant occurredAt) {}
