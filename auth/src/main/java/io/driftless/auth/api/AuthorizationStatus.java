package io.driftless.auth.api;

/**
 * Lifecycle state of an authorization in the Spec 03 saga.
 *
 * <pre>
 *   authorize ─▶ (rule DECLINE / token not ACTIVE) ─▶ DECLINED         [terminal]
 *             └▶ place hold ─▶ AUTHORIZING ─(partner)─┬▶ approve ─▶ AUTHORIZED
 *                                                     ├▶ decline ─▶ DECLINED   [terminal]
 *                                                     └▶ timeout/fail ─▶ COMPENSATING ─▶ REVERSED [terminal]
 *   AUTHORIZED ─▶ capture  ─▶ CAPTURED   [terminal]
 *   AUTHORIZED ─▶ reverse  ─▶ REVERSED   [terminal]
 *   CAPTURED   ─▶ reverse  ─▶ REVERSED   [terminal] (posts the inverse settlement)
 * </pre>
 *
 * <p>{@link #AUTHORIZING} and {@link #COMPENSATING} are the only non-terminal states; both are
 * <em>in-flight</em> markers that the recovery sweep drives to a terminal state after a crash.
 */
public enum AuthorizationStatus {

    /** Hold placed; the bounded partner authorize call is in flight (in-flight, not terminal). */
    AUTHORIZING,

    /** Partner approved; the hold stands until capture or reversal. */
    AUTHORIZED,

    /** Declined by a rule, an inactive token, or the partner; no money moved, any hold released. */
    DECLINED,

    /** Settled: the hold became a posted settlement transaction and was released. Terminal. */
    CAPTURED,

    /** Reversed: the hold was released (and any prior capture posted its inverse). Terminal. */
    REVERSED,

    /** Transient compensation in progress after a partner timeout/failure (in-flight, not terminal). */
    COMPENSATING;

    /** {@code true} when no further saga transition is possible from this state. */
    public boolean isTerminal() {
        return this == DECLINED || this == CAPTURED || this == REVERSED;
    }

    /** {@code true} when this is an in-flight state the recovery sweep must drive to terminal. */
    public boolean isInFlight() {
        return this == AUTHORIZING || this == COMPENSATING;
    }
}
