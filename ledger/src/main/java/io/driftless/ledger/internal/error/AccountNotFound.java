package io.driftless.ledger.internal.error;

import io.driftless.common.error.DomainException;
import io.driftless.common.id.AccountId;

/**
 * Thrown when a {@code PostingLine} or {@code balanceOf} references an account that does not exist.
 *
 * <p>A post must not create entries against an unknown account; the request is rejected and nothing
 * is written. Catchable as {@link DomainException}.
 */
public final class AccountNotFound extends DomainException {

    public AccountNotFound(AccountId id) {
        super("No account found for id %s".formatted(id));
    }
}
