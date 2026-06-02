package io.driftless.ledger.internal.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * JPA row for the {@code transaction} table — one posted, balanced journal batch.
 *
 * <p>The {@code idempotency_key} is unique at the database, so a concurrent replay collides on the
 * constraint rather than producing a double effect. Entries are a lazy, ordered collection;
 * persisting a transaction cascades its legs. Append-only: no setters, no update/delete path.
 *
 * <p>Implements {@link Persistable} with caller-assigned UUID ids so Spring Data {@code save()}
 * issues an {@code INSERT} ({@code persist}) rather than a {@code merge} (which, for a new aggregate
 * with assigned id and a child collection, would try to load non-existent legs and fail).
 */
@Entity
@Table(name = "transaction")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransactionEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "posted_at", nullable = false, updatable = false)
    private Instant postedAt;

    @Column(name = "description", updatable = false, length = 512)
    private String description;

    // Inverse side: JournalEntryEntity.transaction owns the transaction_id column. The collection is
    // ordered and cascades persist so saving a transaction writes its legs in one atomic flush.
    @OneToMany(mappedBy = "transaction", fetch = FetchType.LAZY, cascade = CascadeType.PERSIST, orphanRemoval = false)
    @OrderBy("sequenceNo ASC")
    private List<JournalEntryEntity> entries = new ArrayList<>();

    @Transient
    private boolean newEntity = false;

    public TransactionEntity(UUID id, String idempotencyKey, Instant occurredAt, Instant postedAt, String description) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.occurredAt = occurredAt;
        this.postedAt = postedAt;
        this.description = description;
        this.newEntity = true;
    }

    /** Attach a leg to this batch before persisting. Used only while assembling a fresh post. */
    public void addEntry(JournalEntryEntity entry) {
        entries.add(entry);
    }

    /** Immutable view of the ordered legs. */
    public List<JournalEntryEntity> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    @Override
    public UUID getId() {
        return id;
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
