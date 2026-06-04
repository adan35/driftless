package io.driftless.tokens.api;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * The named transitions of the token lifecycle state machine (Spec 06).
 *
 * <p>Each transition carries the {@link TokenStatus target state} it moves a token to and the set of
 * states it is {@link #isLegalFrom legal from}. Together with {@link TokenStatus} this encodes the
 * whole state machine declaratively, so the service is a thin enforcement of these edges and {@link
 * IllegalTokenTransition} names exactly which transition was rejected.
 *
 * <pre>
 *   create                          → INACTIVE
 *   activate  : INACTIVE            → ACTIVE
 *   suspend   : ACTIVE              → SUSPENDED
 *   resume    : SUSPENDED           → ACTIVE
 *   deactivate: INACTIVE|ACTIVE|SUSPENDED → DEACTIVATED (terminal; no exit)
 * </pre>
 */
public enum TokenTransition {

    /** Provision a brand-new token; legal only when no token yet exists, landing in {@link TokenStatus#INACTIVE}. */
    CREATE(TokenStatus.INACTIVE, EnumSet.noneOf(TokenStatus.class)),

    /** {@link TokenStatus#INACTIVE} → {@link TokenStatus#ACTIVE}. */
    ACTIVATE(TokenStatus.ACTIVE, EnumSet.of(TokenStatus.INACTIVE)),

    /** {@link TokenStatus#ACTIVE} → {@link TokenStatus#SUSPENDED}. */
    SUSPEND(TokenStatus.SUSPENDED, EnumSet.of(TokenStatus.ACTIVE)),

    /** {@link TokenStatus#SUSPENDED} → {@link TokenStatus#ACTIVE}. */
    RESUME(TokenStatus.ACTIVE, EnumSet.of(TokenStatus.SUSPENDED)),

    /** Any non-terminal state → terminal {@link TokenStatus#DEACTIVATED}. */
    DEACTIVATE(TokenStatus.DEACTIVATED, EnumSet.of(TokenStatus.INACTIVE, TokenStatus.ACTIVE, TokenStatus.SUSPENDED));

    private final TokenStatus target;
    private final Set<TokenStatus> legalFrom;

    TokenTransition(TokenStatus target, Set<TokenStatus> legalFrom) {
        this.target = target;
        this.legalFrom = legalFrom;
    }

    /** The state a token is in after this transition succeeds. */
    public TokenStatus target() {
        return target;
    }

    /**
     * Whether this transition may be applied to a token currently in {@code from}.
     *
     * @param from the token's current status (must not be {@code null})
     * @return {@code true} if the edge {@code from → target()} is part of the state machine
     */
    public boolean isLegalFrom(TokenStatus from) {
        Objects.requireNonNull(from, "from");
        return legalFrom.contains(from);
    }
}
