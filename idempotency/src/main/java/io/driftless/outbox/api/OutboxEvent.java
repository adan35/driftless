package io.driftless.outbox.api;

import java.time.Instant;
import java.util.Objects;

/**
 * A domain event to be published exactly once, appended in the same transaction as the state change
 * that produced it.
 *
 * <p>The relay derives delivery ordering from the persisted row's monotonic id (insertion order),
 * with {@code occurredAt} preserved as business time. Consumers must be idempotent: at-least-once
 * delivery plus an idempotent consumer (keyed by the event's persisted id) is effectively-once.
 *
 * @param aggregateType the kind of aggregate the event is about (e.g. {@code "ledger.transaction"},
 *     {@code "auth.authorization"}); the relay preserves ordering within an aggregate
 * @param aggregateId the id of the specific aggregate instance
 * @param eventType the event name (e.g. {@code "AuthorizationCaptured"})
 * @param payloadJson the event payload as canonical JSON (opaque to the outbox)
 * @param occurredAt business time the event occurred, supplied by the caller from the injected {@code
 *     Clock} — never {@code Instant.now()}
 */
public record OutboxEvent(
        String aggregateType, String aggregateId, String eventType, String payloadJson, Instant occurredAt) {

    public OutboxEvent {
        requireText(aggregateType, "aggregateType");
        requireText(aggregateId, "aggregateId");
        requireText(eventType, "eventType");
        requireText(payloadJson, "payloadJson");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
