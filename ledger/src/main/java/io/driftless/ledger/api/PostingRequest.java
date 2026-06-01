package io.driftless.ledger.api;

import java.time.Instant;
import java.util.List;

/**
 * A request to post one balanced, atomic transaction (&gt;= 2 lines).
 *
 * <p>{@code SUM(debits) == SUM(credits)} per currency, else the request is rejected. The {@code
 * idempotencyKey} is honored: a replay returns the original {@link PostingResult}. {@code
 * occurredAt} is business time, taken from the injected {@code Clock} at the caller.
 */
public record PostingRequest(String idempotencyKey, Instant occurredAt, String description, List<PostingLine> lines) {}
