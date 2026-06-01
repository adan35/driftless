package io.driftless.common.id;

import java.util.Objects;
import java.util.UUID;

/** Strongly-typed identifier for a single journal entry (one leg of a transaction). */
public record EntryId(UUID value) {

    public EntryId {
        Objects.requireNonNull(value, "value");
    }

    /** Wrap an existing UUID. */
    public static EntryId of(UUID value) {
        return new EntryId(value);
    }

    /** Wrap a UUID parsed from its canonical string form. */
    public static EntryId of(String value) {
        return new EntryId(UUID.fromString(value));
    }

    /** Mint a fresh random identifier. */
    public static EntryId newId() {
        return new EntryId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
