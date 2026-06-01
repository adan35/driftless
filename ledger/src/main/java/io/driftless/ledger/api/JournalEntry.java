package io.driftless.ledger.api;

import io.driftless.common.id.AccountId;
import io.driftless.common.id.EntryId;
import io.driftless.common.id.TxId;
import io.driftless.common.money.Money;

/**
 * One immutable, append-only leg of a posted transaction.
 *
 * <p>Mirrors the {@code journal_entry} row in Spec 01's data model. {@code amount} is positive minor
 * units; {@link Direction} carries the sign. {@code sequenceNo} orders the legs within their
 * transaction.
 */
public record JournalEntry(
        EntryId id, TxId transactionId, AccountId account, Direction direction, Money amount, int sequenceNo) {}
