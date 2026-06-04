package io.driftless.idempotency.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * JPA row for {@code idempotency_record} — one claimed idempotency key within a scope.
 *
 * <p>A row is inserted as {@link IdempotencyStatus#IN_PROGRESS} in the same transaction as the
 * business write; the composite primary key {@code (scope, idempotency_key)} makes a concurrent
 * duplicate collide at the database rather than run the operation twice. On success the same
 * transaction transitions the row to {@link IdempotencyStatus#COMPLETED} and stores the serialized
 * result. The status/completion columns are the only mutable state; the rest is append-only.
 *
 * <p>Implements {@link Persistable} with a caller-assigned composite id so a fresh {@code save()}
 * issues an {@code INSERT} (whose unique-constraint violation we use as the concurrency backstop)
 * rather than a {@code merge}-with-preceding-SELECT.
 */
@Entity
@Table(name = "idempotency_record")
@IdClass(IdempotencyRecordId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecordEntity implements Persistable<IdempotencyRecordId> {

    @Id
    @Column(name = "scope", nullable = false, updatable = false, length = 128)
    private String scope;

    @Id
    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, updatable = false, length = 128)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private IdempotencyStatus status;

    @Column(name = "response_blob")
    private String responseBlob;

    @Column(name = "response_type", length = 512)
    private String responseType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Transient
    private boolean newEntity = false;

    /** Claim a key: a fresh {@code IN_PROGRESS} record to insert in the business transaction. */
    public IdempotencyRecordEntity(String scope, String idempotencyKey, String requestHash, Instant createdAt) {
        this.scope = scope;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.status = IdempotencyStatus.IN_PROGRESS;
        this.createdAt = createdAt;
        this.newEntity = true;
    }

    /**
     * Transition this claimed record to {@code COMPLETED}, recording the serialized result so a later
     * retry can replay it without re-running the operation. Idempotent on the status column only.
     */
    public void complete(String responseBlob, String responseType, Instant completedAt) {
        this.responseBlob = responseBlob;
        this.responseType = responseType;
        this.status = IdempotencyStatus.COMPLETED;
        this.completedAt = completedAt;
    }

    @Override
    public IdempotencyRecordId getId() {
        return new IdempotencyRecordId(scope, idempotencyKey);
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.newEntity = false;
    }
}
