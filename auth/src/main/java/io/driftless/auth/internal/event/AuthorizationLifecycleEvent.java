package io.driftless.auth.internal.event;

import io.driftless.auth.api.AuthorizationStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * The auth-saga lifecycle event, serialized as the payload of the outbox row appended atomically with
 * each state change (Spec 03 propagation, via the Spec 02 transactional outbox).
 *
 * <p>One event type per terminal/decisive transition is carried by {@link #eventType}: {@code
 * AuthorizationAuthorized}, {@code AuthorizationDeclined}, {@code AuthorizationCaptured} and {@code
 * AuthorizationReversed} (the compensating reversal). Consumers key idempotency on the outbox row id.
 *
 * @param authorizationId the authorization whose state changed
 * @param status the new state after the transition
 * @param partnerRef the partner reference, when known at the time of the event
 * @param reason the machine-readable reason on a decline/reversal, when applicable
 * @param amountMinor the authorization amount (minor units)
 * @param currency ISO-4217 currency code
 * @param occurredAt business time of the change, sourced from the injected {@code Clock}
 */
public record AuthorizationLifecycleEvent(
        UUID authorizationId,
        AuthorizationStatus status,
        String partnerRef,
        String reason,
        long amountMinor,
        String currency,
        Instant occurredAt) {

    /** The outbox {@code aggregate_type} for auth-saga events. */
    public static final String AGGREGATE_TYPE = "auth.authorization";

    /** Event name emitted when the partner approves and the hold stands. */
    public static final String AUTHORIZED = "AuthorizationAuthorized";

    /** Event name emitted on any decline (rule, inactive token, or partner). */
    public static final String DECLINED = "AuthorizationDeclined";

    /** Event name emitted when a hold is settled into a posted transaction. */
    public static final String CAPTURED = "AuthorizationCaptured";

    /** Event name emitted by the compensating reversal (timeout/failure) and explicit reverse. */
    public static final String REVERSED = "AuthorizationReversed";
}
