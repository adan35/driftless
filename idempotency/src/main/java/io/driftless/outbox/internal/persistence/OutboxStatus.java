package io.driftless.outbox.internal.persistence;

/** Delivery state of an {@link OutboxEventEntity}: appended, then published exactly once. */
public enum OutboxStatus {

    /** Appended in the caller's transaction; awaiting relay. */
    PENDING,

    /** Successfully handed to the publisher; will not be published again. */
    PUBLISHED
}
