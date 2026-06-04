package io.driftless.tokens.internal.event;

import io.driftless.common.id.TokenId;
import io.driftless.tokens.api.TokenStatus;
import java.time.Instant;

/**
 * The {@code TokenStatusChanged} domain event, serialized as the payload of the outbox row appended
 * atomically with each token transition (Spec 06 status propagation).
 *
 * <p>Downstream consumers (e.g. the auth path gating on {@code status == ACTIVE}) read this to stay
 * consistent with the token's lifecycle. {@code from} is {@code null} for the initial {@code create}
 * — the token had no prior state.
 *
 * @param tokenId the token whose status changed
 * @param from the prior status, or {@code null} for the create event
 * @param to the new status after the transition
 * @param occurredAt business time of the change, sourced from the injected {@code Clock}
 */
public record TokenStatusChanged(TokenId tokenId, TokenStatus from, TokenStatus to, Instant occurredAt) {

    /** The outbox {@code aggregate_type} for token events. */
    public static final String AGGREGATE_TYPE = "tokens.token";

    /** The outbox {@code event_type} carried by this event. */
    public static final String EVENT_TYPE = "TokenStatusChanged";
}
