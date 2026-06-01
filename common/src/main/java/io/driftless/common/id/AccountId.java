package io.driftless.common.id;

import java.util.Objects;
import java.util.UUID;

/** Strongly-typed identifier for a ledger account. */
public record AccountId(UUID value) {

    public AccountId {
        Objects.requireNonNull(value, "value");
    }

    /** Wrap an existing UUID. */
    public static AccountId of(UUID value) {
        return new AccountId(value);
    }

    /** Wrap a UUID parsed from its canonical string form. */
    public static AccountId of(String value) {
        return new AccountId(UUID.fromString(value));
    }

    /** Mint a fresh random identifier. */
    public static AccountId newId() {
        return new AccountId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
