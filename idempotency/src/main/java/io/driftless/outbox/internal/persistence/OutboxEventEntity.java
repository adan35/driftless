package io.driftless.outbox.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * JPA row for {@code outbox_event} — one event awaiting (or having completed) exactly-once delivery.
 *
 * <p>Appended as {@link OutboxStatus#PENDING} in the caller's transaction, atomically with the state
 * change it describes. The relay later transitions it to {@link OutboxStatus#PUBLISHED} and stamps
 * {@code published_at}. The id is a database identity (monotonic), which the relay uses to both order
 * delivery and page deterministically; everything but the status/published_at/attempts columns is
 * append-only.
 */
@Entity
@Table(name = "outbox_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 128)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false, length = 255)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 128)
    private String eventType;

    @Column(name = "payload_json", nullable = false, updatable = false)
    private String payloadJson;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OutboxStatus status;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    /** Append a fresh PENDING event in the caller's transaction. */
    public OutboxEventEntity(
            String aggregateType, String aggregateId, String eventType, String payloadJson, Instant occurredAt) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payloadJson = payloadJson;
        this.occurredAt = occurredAt;
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
    }

    /** Count one delivery attempt — incremented whether or not the publish succeeds. */
    public void recordAttempt() {
        this.attempts++;
    }

    /** Mark the event delivered; idempotent on the status column. */
    public void markPublished(Instant publishedAt) {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = publishedAt;
    }
}
