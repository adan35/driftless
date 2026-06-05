package io.driftless.auth.internal.persistence;

import io.driftless.auth.api.HoldStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * JPA row for {@code hold} — the available-vs-posted seam.
 *
 * <p>An {@link HoldStatus#ACTIVE} row reduces the account's available balance (the ledger {@code
 * HoldView} sums active holds) while posted is unchanged. The row is never deleted: it is flipped to
 * {@link HoldStatus#RELEASED} (stamping {@code releasedAt}) when its authorization is captured or
 * reversed, which restores available. This preserves the audit trail (immutability).
 */
@Entity
@Table(name = "hold")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HoldEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "authorization_id", nullable = false, updatable = false)
    private UUID authorizationId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 8)
    private HoldStatus status;

    @Column(name = "placed_at", nullable = false, updatable = false)
    private Instant placedAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Transient
    private boolean newEntity = false;

    /** Place a fresh ACTIVE hold against the account. */
    public HoldEntity(
            UUID id, UUID authorizationId, UUID accountId, long amountMinor, String currency, Instant placedAt) {
        this.id = id;
        this.authorizationId = authorizationId;
        this.accountId = accountId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.status = HoldStatus.ACTIVE;
        this.placedAt = placedAt;
        this.newEntity = true;
    }

    /** Flip the hold to RELEASED, stamping the business time it stopped counting against available. */
    public void release(Instant releasedAt) {
        if (this.status == HoldStatus.ACTIVE) {
            this.status = HoldStatus.RELEASED;
            this.releasedAt = releasedAt;
        }
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
