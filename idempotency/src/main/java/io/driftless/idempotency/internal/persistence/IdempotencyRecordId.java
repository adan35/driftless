package io.driftless.idempotency.internal.persistence;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for {@link IdempotencyRecordEntity}: an idempotency key is unique within its
 * scope (endpoint). Used as the {@code @IdClass} so the unique constraint on {@code (scope, key)} is
 * the database-level backstop against concurrent duplicates.
 */
public record IdempotencyRecordId(String scope, String idempotencyKey) implements Serializable {

    public IdempotencyRecordId {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    }

    /** No-arg form required by JPA for {@code @IdClass} instantiation. */
    public IdempotencyRecordId() {
        this("", "");
    }
}
