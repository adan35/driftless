package io.driftless.auth.api;

import io.driftless.common.error.DomainException;
import io.driftless.common.id.AccountId;

/**
 * Thrown when a REST request references a ledger account id that does not exist (funding a missing
 * account, reading its details or statement). The web layer maps this to {@code 404 Not Found} with
 * the stable error code {@code ACCOUNT_NOT_FOUND}.
 */
public class UnknownAccountException extends DomainException {

    public UnknownAccountException(AccountId account) {
        super("No account found with id '%s'".formatted(account));
    }
}
