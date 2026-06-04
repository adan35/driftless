package io.driftless.idempotency.internal.persistence;

/** Lifecycle of an {@link IdempotencyRecordEntity}: claimed, then completed exactly once. */
public enum IdempotencyStatus {

    /** The key has been claimed and the operation is running in the claiming transaction. */
    IN_PROGRESS,

    /** The operation committed; {@code response_blob} holds the result to replay. */
    COMPLETED
}
