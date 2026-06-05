package io.driftless.outbox.spi;

import java.time.Instant;

/**
 * A published, read-only view of one outbox event handed to every {@link OutboxPublicationListener}
 * at the moment the relay delivers it. It mirrors the row the {@code EventPublisher} seam carries,
 * but lives on the {@code spi} so cross-cutting consumers (Spec 08 observability) can react to the
 * domain-event stream <em>through a published contract</em> rather than binding to the outbox's
 * internal types.
 *
 * <p>The {@code id} is the persisted, monotonic event id — the stable key an idempotent consumer
 * dedups on. Because relay delivery is at-least-once (a crash between publish and mark-published
 * re-presents the event), listeners <strong>must</strong> dedup on {@code id} so a metric counter
 * increments exactly once per event.
 *
 * @param id the persisted outbox event id; the consumer's dedup key
 * @param aggregateType the kind of aggregate the event is about (e.g. {@code auth.authorization})
 * @param aggregateId the id of the specific aggregate instance
 * @param eventType the event name (e.g. {@code AuthorizationReversed})
 * @param payloadJson the event payload as JSON
 * @param occurredAt business time the event occurred, from the injected {@code Clock}
 */
public record OutboxPublication(
        long id, String aggregateType, String aggregateId, String eventType, String payloadJson, Instant occurredAt) {}
