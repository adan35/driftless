package io.driftless.common.id;

import java.util.Objects;
import java.util.UUID;

/** Strongly-typed identifier for a payment token in the tokenization lifecycle. */
public record TokenId(UUID value) {

    public TokenId {
        Objects.requireNonNull(value, "value");
    }

    /** Wrap an existing UUID. */
    public static TokenId of(UUID value) {
        return new TokenId(value);
    }

    /** Wrap a UUID parsed from its canonical string form. */
    public static TokenId of(String value) {
        return new TokenId(UUID.fromString(value));
    }

    /** Mint a fresh random identifier. */
    public static TokenId newId() {
        return new TokenId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
