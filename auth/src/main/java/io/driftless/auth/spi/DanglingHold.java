package io.driftless.auth.spi;

import io.driftless.auth.api.AuthorizationStatus;
import java.util.UUID;

/**
 * A <em>dangling hold</em>: a {@code hold} row still {@link io.driftless.auth.api.HoldStatus#ACTIVE}
 * whose owning authorization has reached a terminal state ({@link AuthorizationStatus#CAPTURED},
 * {@link AuthorizationStatus#REVERSED} or {@link AuthorizationStatus#DECLINED}). It means available
 * balance is still being reduced by a hold that should have been released — the real defect a hold
 * reconciliation check must catch, detected from an <em>independent</em> source of truth (the
 * authorization's status), not by re-deriving available from the same hold rows.
 *
 * @param holdId the offending ACTIVE hold
 * @param authorizationId its owning authorization
 * @param accountId the account whose available balance is wrongly reduced
 * @param currency the hold currency
 * @param amountMinor the hold amount, in minor units
 * @param authorizationStatus the (terminal) status that makes the hold dangling
 */
public record DanglingHold(
        UUID holdId,
        UUID authorizationId,
        UUID accountId,
        String currency,
        long amountMinor,
        AuthorizationStatus authorizationStatus) {}
