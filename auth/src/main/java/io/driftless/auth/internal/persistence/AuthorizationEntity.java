package io.driftless.auth.internal.persistence;

import io.driftless.auth.api.AuthorizationStatus;
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
 * JPA row for {@code authorization} — the saga aggregate, advanced through the {@link
 * AuthorizationStatus} state machine.
 *
 * <p>Only {@code status}, {@code partnerRef}, {@code declineReason} and {@code updatedAt} are
 * mutable; the request facts (account, amount, currency, mcc, merchant, token, card reference, key)
 * are write-once. {@code cardRef} is a log-safe reference to the underlying card (never the raw PAN),
 * persisted so the recovery sweep can address the partner without re-resolving a token.
 *
 * <p>Concurrent transitions on one authorization serialize on a {@code SELECT ... FOR UPDATE} row
 * lock (see {@code AuthorizationRepository#findByIdForUpdate}); the {@link Version} column adds
 * optimistic-locking defence-in-depth so a lost update can never resurrect a terminal authorization.
 *
 * <p>Implements {@link Persistable} with a caller-assigned id so a fresh {@code save()} issues an
 * {@code INSERT} rather than a {@code merge}-with-preceding-SELECT.
 */
@Entity
@Table(name = "`authorization`")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuthorizationEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "mcc", updatable = false, length = 4)
    private String mcc;

    @Column(name = "merchant_id", updatable = false, length = 64)
    private String merchantId;

    @Column(name = "token_id", updatable = false)
    private UUID tokenId;

    @Column(name = "card_ref", nullable = false, updatable = false, length = 255)
    private String cardRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AuthorizationStatus status;

    @Column(name = "partner_ref", length = 64)
    private String partnerRef;

    /**
     * The amount actually settled by a capture (minor units), or {@code null} until/unless a capture
     * settles. A reversal of a captured authorization posts the inverse of <em>this</em> amount, so a
     * partial capture is reversed for exactly what it captured (review C1), never the authorized total.
     */
    @Column(name = "captured_amount_minor")
    private Long capturedAmountMinor;

    @Column(name = "decline_reason", length = 64)
    private String declineReason;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Transient
    private boolean newEntity = false;

    /** Provision a fresh authorization to insert at the start of the saga. */
    public AuthorizationEntity(
            UUID id,
            UUID accountId,
            long amountMinor,
            String currency,
            String mcc,
            String merchantId,
            UUID tokenId,
            String cardRef,
            AuthorizationStatus status,
            String idempotencyKey,
            Instant now) {
        this.id = id;
        this.accountId = accountId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.mcc = mcc;
        this.merchantId = merchantId;
        this.tokenId = tokenId;
        this.cardRef = cardRef;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = now;
        this.updatedAt = now;
        this.newEntity = true;
    }

    /** Move the authorization to {@code target}, stamping the business time of the change. */
    public void transitionTo(AuthorizationStatus target, Instant changedAt) {
        this.status = target;
        this.updatedAt = changedAt;
    }

    /** Record the partner-assigned reference (idempotent: keeps the first non-null value). */
    public void recordPartnerRef(String ref) {
        if (ref != null && this.partnerRef == null) {
            this.partnerRef = ref;
        }
    }

    /** Record the amount a capture actually settled (minor units), so a later reversal inverts it. */
    public void recordCapturedAmount(long capturedMinor) {
        this.capturedAmountMinor = capturedMinor;
    }

    /** Record the machine-readable decline reason. */
    public void recordDeclineReason(String reason) {
        this.declineReason = reason;
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
