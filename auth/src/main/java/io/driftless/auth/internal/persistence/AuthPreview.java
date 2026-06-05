package io.driftless.auth.internal.persistence;

import io.driftless.auth.api.AuthorizationStatus;

/**
 * A read-only scalar projection of the saga aggregate used to validate a capture/reverse and resolve
 * the partner reference <em>before</em> the bounded partner HTTP call — without loading the entity
 * into the persistence context and without holding a {@code SELECT ... FOR UPDATE} row lock across the
 * network (review W1). The mutating step then re-loads the row {@code FOR UPDATE} and re-checks the
 * live status, so a concurrent transition can never be acted on stale state.
 *
 * @param status the live lifecycle status
 * @param partnerRef the partner reference, when one is known (else {@code null})
 * @param currency ISO-4217 currency code of the authorization
 * @param amountMinor the authorized amount (minor units)
 * @param capturedAmountMinor the amount a capture settled (minor units), or {@code null} if uncaptured
 */
public record AuthPreview(
        AuthorizationStatus status, String partnerRef, String currency, long amountMinor, Long capturedAmountMinor) {}
