package io.driftless.tokens.internal.persistence;

import io.driftless.tokens.api.TokenStatus;
import io.driftless.tokens.api.TokenTransition;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * JPA row for {@code token} — the issuer-side mapping of a card reference to a wallet/device token,
 * with its own lifecycle {@link TokenStatus}.
 *
 * <p>{@code cardRef} is a non-sensitive reference to the underlying card (never the raw PAN), so the
 * raw card number never reaches a persisted column. The {@code status}, {@code lastTransition} and
 * {@code updatedAt} columns are the only mutable state: each successful transition moves the status
 * along a legal edge, records the {@link TokenTransition} that advanced it, and stamps {@code
 * updatedAt} from the injected {@code Clock}. {@code lastTransition} is the deterministic anchor the
 * keyless surface uses to tell a genuine same-transition replay from a fresh illegal transition. The
 * append-only audit of those moves lives in {@code token_status_history}.
 *
 * <p>Concurrent transitions on the same token are serialized by a pessimistic write lock (see {@code
 * TokenRepository.findByIdForUpdate}); the {@link Version} column adds optimistic-locking
 * defence-in-depth so a lost update can never silently resurrect a terminal token.
 *
 * <p>Implements {@link Persistable} with a caller-assigned id so a fresh {@code save()} issues an
 * {@code INSERT} rather than a {@code merge}-with-preceding-SELECT.
 */
@Entity
@Table(name = "token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TokenEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "card_ref", nullable = false, updatable = false, length = 255)
    private String cardRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TokenStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_transition", nullable = false, length = 16)
    private TokenTransition lastTransition;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Transient
    private boolean newEntity = false;

    /** Provision a fresh token to insert in the create transaction. */
    public TokenEntity(
            UUID id,
            String cardRef,
            TokenStatus status,
            TokenTransition lastTransition,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.cardRef = cardRef;
        this.status = status;
        this.lastTransition = lastTransition;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.newEntity = true;
    }

    /** Move the token to {@code target} via {@code transition}, stamping the business time of the change. */
    public void transitionTo(TokenStatus target, TokenTransition transition, Instant changedAt) {
        this.status = target;
        this.lastTransition = transition;
        this.updatedAt = changedAt;
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
