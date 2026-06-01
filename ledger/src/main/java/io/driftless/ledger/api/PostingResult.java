package io.driftless.ledger.api;

import io.driftless.common.id.TxId;
import java.time.Instant;

/**
 * Result of a successful (or replayed) post.
 *
 * @param replayed {@code true} when returned from an idempotent replay
 */
public record PostingResult(TxId transactionId, Instant postedAt, boolean replayed) {}
