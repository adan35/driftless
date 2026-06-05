package io.driftless.auth.internal.persistence;

import java.util.UUID;

/**
 * Internal projection of a summed minor-unit amount grouped by {@code (account, currency)} — the
 * aggregate building block for the hold cross-foot the {@link io.driftless.auth.spi.HoldReconView}
 * exposes (active-hold sums vs authorized-amount sums).
 *
 * @param accountId the account
 * @param currency the currency
 * @param totalMinor the summed amount in minor units
 */
public record AccountCurrencyMinor(UUID accountId, String currency, long totalMinor) {}
