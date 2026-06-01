package io.driftless.ledger.api;

import io.driftless.common.error.DomainException;

/**
 * Thrown by {@link Ledger#post} when a {@link PostingRequest} is not balanced — that is, when {@code
 * SUM(debits) != SUM(credits)} for some currency.
 *
 * <p>This enforces invariant 1 (Balance): an unbalanced request is rejected and nothing is written.
 */
public final class BalanceInvariantViolation extends DomainException {

    public BalanceInvariantViolation(String message) {
        super(message);
    }
}
