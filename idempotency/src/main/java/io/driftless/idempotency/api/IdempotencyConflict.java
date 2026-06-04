package io.driftless.idempotency.api;

import io.driftless.common.error.DomainException;

/**
 * Thrown when an idempotency key is reused with a <em>different</em> request fingerprint than the one
 * it was first seen with.
 *
 * <p>A retry of the same logical request (same key, same {@code requestHash}) is a replay and returns
 * the original result. A key reused with a different body is not a retry — it is a client error and
 * must not silently overwrite or re-run anything. The REST layer (Spec 03) maps this to {@code 409
 * Conflict}; here it is a typed domain error so callers can distinguish it from a genuine replay.
 */
public class IdempotencyConflict extends DomainException {

    private final String key;
    private final String expectedRequestHash;
    private final String actualRequestHash;

    public IdempotencyConflict(String key, String expectedRequestHash, String actualRequestHash) {
        super("Idempotency key '%s' was first used with request hash '%s' but is now replayed with '%s'"
                .formatted(key, expectedRequestHash, actualRequestHash));
        this.key = key;
        this.expectedRequestHash = expectedRequestHash;
        this.actualRequestHash = actualRequestHash;
    }

    /** The conflicting idempotency key. */
    public String key() {
        return key;
    }

    /** The request hash the key was originally recorded with. */
    public String expectedRequestHash() {
        return expectedRequestHash;
    }

    /** The request hash supplied on the conflicting replay. */
    public String actualRequestHash() {
        return actualRequestHash;
    }
}
