package io.driftless.tokens.api;

/**
 * Lifecycle state of a payment token.
 *
 * <p>The legal transitions form a strict state machine (Spec 06): a freshly created token is {@link
 * #INACTIVE}; {@code activate} moves it to {@link #ACTIVE}; {@code suspend}/{@code resume} toggle
 * between {@link #ACTIVE} and {@link #SUSPENDED}; {@code deactivate} moves any non-terminal state to
 * {@link #DEACTIVATED}, which is terminal (no exit). Any other transition is rejected with an {@link
 * IllegalTokenTransition} and changes nothing.
 */
public enum TokenStatus {

    /** Created but not yet usable; the only state from which {@code activate} is legal. */
    INACTIVE,

    /** Live and able to transact; reachable from {@code activate} or {@code resume}. */
    ACTIVE,

    /** Temporarily blocked; reachable only from {@code suspend} and exited only by {@code resume}. */
    SUSPENDED,

    /** Permanently retired; terminal, with no legal exit transition. */
    DEACTIVATED
}
