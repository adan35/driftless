package io.driftless.auth.spi;

import java.util.UUID;

/**
 * A per-(account, currency) hold cross-foot mismatch: the sum of currently-{@code ACTIVE} hold
 * amounts disagrees with the sum of {@code amount} of authorizations still in {@link
 * io.driftless.auth.api.AuthorizationStatus#AUTHORIZED}.
 *
 * <p>For a consistent saga these are equal (each AUTHORIZED authorization carries exactly one ACTIVE
 * hold of its amount). A positive difference means a hold is held against a non-AUTHORIZED
 * authorization (a dangling hold); a negative difference means an AUTHORIZED authorization has lost
 * its hold. Either is independent of the ledger's {@code balanceOf} derivation.
 *
 * @param accountId the account whose holds cross-foot fails
 * @param currency the currency
 * @param activeHoldMinor sum of ACTIVE hold amounts on the account, in minor units
 * @param authorizedAmountMinor sum of AUTHORIZED authorization amounts on the account, in minor units
 */
public record HoldBalanceMismatch(UUID accountId, String currency, long activeHoldMinor, long authorizedAmountMinor) {

    /** Signed difference {@code activeHoldMinor - authorizedAmountMinor} (non-zero by definition here). */
    public long differenceMinor() {
        return activeHoldMinor - authorizedAmountMinor;
    }
}
