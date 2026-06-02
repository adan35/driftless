package io.driftless.ledger.internal.error;

import io.driftless.common.error.DomainException;
import io.driftless.common.id.TxId;

/**
 * Thrown by the ledger when a transaction id has no posted transaction.
 *
 * <p>The frozen {@code Ledger#findTransaction} returns a bare {@code Transaction}; absence is an
 * exceptional, typed domain error rather than a {@code null} return. Catchable as {@link
 * DomainException}.
 */
public final class TransactionNotFound extends DomainException {

    public TransactionNotFound(TxId id) {
        super("No transaction found for id %s".formatted(id));
    }
}
