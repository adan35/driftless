package io.driftless.recon.api;

/**
 * The four reconciliation checks the Spec 07 balance-proof job runs by summation over committed
 * state. Each is a falsifiable assertion about the ledger / saga / outbox; a failure surfaces the
 * offending accounts/transactions for root-cause analysis and never triggers an auto-edit.
 */
public enum ReconCheck {

    /** Global zero: the signed sum of every journal entry is {@code 0} per currency, ledger-wide. */
    GLOBAL_ZERO,

    /**
     * Per-transaction balance: every single posted transaction's signed legs net to {@code 0} per
     * currency. Strictly stronger than {@link #GLOBAL_ZERO} alone — it catches an offending
     * transaction even when two unbalanced transactions coincidentally offset in the global sum.
     */
    PER_TRANSACTION,

    /**
     * Hold consistency: no ACTIVE hold is dangling (its authorization is terminal) and, per account,
     * the sum of ACTIVE holds cross-foots the sum of AUTHORIZED authorization amounts — checked from
     * the authorizations' own statuses (an independent source of truth), not by re-deriving available
     * from the same hold rows.
     */
    HOLD_CONSISTENCY,

    /** Outbox consistency: no {@code PENDING} outbox event is older than the configured threshold. */
    OUTBOX_CONSISTENCY
}
