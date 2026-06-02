package io.driftless.ledger.internal.persistence;

import io.driftless.ledger.api.AccountType;
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
 * JPA row for the {@code account} table — an immutable account definition.
 *
 * <p>This is an internal persistence detail and must never cross the frozen {@code
 * io.driftless.ledger.api} boundary; the {@code LedgerService} maps it to/from the api {@code
 * Account} record. There is deliberately no balance column — balance is derived by summation.
 *
 * <p>Implements {@link Persistable} with caller-assigned UUID ids so a fresh {@code save()} issues a
 * plain {@code INSERT} rather than a {@code merge}-with-preceding-SELECT.
 */
@Entity
@Table(name = "account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccountEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 16)
    private AccountType type;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "name", nullable = false, updatable = false)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean newEntity = false;

    public AccountEntity(UUID id, AccountType type, String currency, String name, Instant createdAt) {
        this.id = id;
        this.type = type;
        this.currency = currency;
        this.name = name;
        this.createdAt = createdAt;
        this.newEntity = true;
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
