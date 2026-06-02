package io.driftless.ledger.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A request to post one balanced, atomic transaction (&gt;= 2 lines).
 *
 * <p>{@code SUM(debits) == SUM(credits)} per currency, else the request is rejected. The {@code
 * idempotencyKey} is honored: a replay returns the original {@link PostingResult}. {@code
 * occurredAt} is business time, taken from the injected {@code Clock} at the caller.
 *
 * <p>The compact constructor enforces the structural pre-conditions this contract has always
 * documented — a non-blank idempotency key, a non-null business time, and at least two legs — and
 * defensively copies {@code lines} so the request is genuinely immutable. These are shape-preserving
 * guards: the record's components and accessors are unchanged. The semantic balance and
 * currency-match checks belong to {@link Ledger#post} (they may throw {@link
 * BalanceInvariantViolation}).
 */
public record PostingRequest(String idempotencyKey, Instant occurredAt, String description, List<PostingLine> lines) {

    public PostingRequest {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(lines, "lines");
        if (lines.size() < 2) {
            throw new IllegalArgumentException(
                    "a PostingRequest must have at least 2 lines (was " + lines.size() + ")");
        }
        lines = List.copyOf(lines);
    }
}
