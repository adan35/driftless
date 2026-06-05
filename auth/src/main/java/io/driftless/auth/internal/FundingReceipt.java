package io.driftless.auth.internal;

import java.time.Instant;
import java.util.UUID;

/**
 * Result of a successful (or replayed) account funding: the posted ledger transaction id and its
 * posted business time.
 *
 * @param transactionId the balanced funding transaction's id
 * @param postedAt the ledger {@code postedAt} of that transaction
 */
public record FundingReceipt(UUID transactionId, Instant postedAt) {}
