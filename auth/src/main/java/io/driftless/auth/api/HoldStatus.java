package io.driftless.auth.api;

/**
 * Lifecycle state of a hold against an account's available balance.
 *
 * <p>An {@link #ACTIVE} hold reduces available (it is summed by the ledger {@code HoldView}) while
 * leaving posted unchanged. A hold is never deleted — it is moved to {@link #RELEASED} when its
 * authorization is captured or reversed, which restores available. This append-then-flip model keeps
 * the audit trail intact (immutability).
 */
public enum HoldStatus {

    /** Counts against available balance. */
    ACTIVE,

    /** No longer counts against available; released by a capture or reversal. */
    RELEASED
}
