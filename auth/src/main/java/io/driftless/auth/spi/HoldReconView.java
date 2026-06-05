package io.driftless.auth.spi;

import java.util.List;

/**
 * Read-only SPI the auth module (Spec 03) publishes for hold/authorization <em>reconciliation</em>
 * (Spec 07). It exposes an <strong>independent</strong> notion of what active holds should be — the
 * authorizations' own statuses — so a hold consistency check can detect a phantom/dangling hold
 * instead of re-deriving available balance from the same hold rows (which is tautological).
 *
 * <p>Strictly read-only: reconciliation observes committed holds and authorizations, it never edits
 * them. Defined here on the {@code spi} so the recon module integrates through a published contract
 * rather than binding to the auth module's internal JPA schema.
 */
public interface HoldReconView {

    /**
     * Every {@code ACTIVE} hold whose owning authorization is in a terminal state — the precise
     * dangling-hold offenders. Empty for a consistent saga.
     */
    List<DanglingHold> findDanglingHolds();

    /**
     * Per-(account, currency) cross-foot mismatches between the sum of {@code ACTIVE} hold amounts and
     * the sum of {@code AUTHORIZED} authorization amounts. Empty for a consistent saga.
     */
    List<HoldBalanceMismatch> findHoldBalanceMismatches();
}
