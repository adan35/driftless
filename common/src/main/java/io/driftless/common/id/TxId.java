package io.driftless.common.id;

import java.util.Objects;
import java.util.UUID;

/** Strongly-typed identifier for a posted transaction (journal batch). */
public record TxId(UUID value) {

    public TxId {
        Objects.requireNonNull(value, "value");
    }

    /** Wrap an existing UUID. */
    public static TxId of(UUID value) {
        return new TxId(value);
    }

    /** Wrap a UUID parsed from its canonical string form. */
    public static TxId of(String value) {
        return new TxId(UUID.fromString(value));
    }

    /** Mint a fresh random identifier. */
    public static TxId newId() {
        return new TxId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
