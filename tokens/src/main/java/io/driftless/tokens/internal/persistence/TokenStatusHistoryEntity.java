package io.driftless.tokens.internal.persistence;

import io.driftless.tokens.api.TokenStatus;
import io.driftless.tokens.api.TokenTransition;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * JPA row for {@code token_status_history} — one immutable, append-only entry per successful token
 * transition (including the initial {@code CREATE}).
 *
 * <p>This is the token lifecycle's audit trail: rows are only ever inserted, never updated or
 * deleted (enforced defence-in-depth by a database trigger in the migration). {@code fromStatus} is
 * {@code null} for the {@code CREATE} entry — the token had no prior state.
 */
@Entity
@Table(name = "token_status_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TokenStatusHistoryEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "token_id", nullable = false, updatable = false)
    private UUID tokenId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false, length = 16)
    private TokenStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false, length = 16)
    private TokenStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "transition", nullable = false, updatable = false, length = 16)
    private TokenTransition transition;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    /** Record a transition that just occurred. */
    public TokenStatusHistoryEntity(
            UUID id,
            UUID tokenId,
            TokenStatus fromStatus,
            TokenStatus toStatus,
            TokenTransition transition,
            Instant changedAt) {
        this.id = id;
        this.tokenId = tokenId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.transition = transition;
        this.changedAt = changedAt;
    }
}
