package io.driftless.rules.velocity;

import io.driftless.common.id.AccountId;
import io.driftless.common.money.Money;
import io.driftless.rules.api.VelocitySnapshot;
import java.time.Instant;
import java.util.Currency;

/**
 * The velocity counter seam: records authorizations and produces the rolling-window {@link
 * VelocitySnapshot} the saga passes into {@link io.driftless.rules.api.RuleEngine#evaluate}.
 *
 * <p>This is the engine's one piece of mutable state. The contract has two non-negotiable properties
 * the auth saga (Spec 03) relies on:
 *
 * <ol>
 *   <li><b>Idempotent recording</b> — {@link #record} is keyed by a unique authorization id; a replay
 *       with the same id is a no-op, so a retried authorization is counted exactly once.
 *   <li><b>No hot-path DB aggregation</b> — {@link #snapshot} answers from an in-memory window, not a
 *       {@code GROUP BY} per authorization.
 * </ol>
 *
 * <p>The MVP implementation is in-memory ({@link InMemoryVelocityStore}); a Redis/persistence
 * implementation can replace it behind this interface without touching the engine or the saga.
 */
public interface VelocityStore {

    /**
     * Record one authorization toward the account's velocity window, idempotently.
     *
     * @param authorizationId globally-unique id of the authorization; a second call with the same id
     *     records nothing (so retries do not double-count)
     * @param account the account the authorization is drawn against
     * @param amount the authorized amount (its currency is the account's settlement currency)
     * @param occurredAt business time of the authorization, from the caller's injected {@code Clock}
     * @return {@code true} if this call recorded a new authorization, {@code false} if it was a replay
     */
    boolean record(String authorizationId, AccountId account, Money amount, Instant occurredAt);

    /**
     * Snapshot the account's activity within the rolling window ending at {@code now}.
     *
     * @param account the account to summarize
     * @param currency the settlement currency the summed {@code total} is expressed in
     * @param now the window's right edge, from the caller's injected {@code Clock}
     * @return count and summed amount of authorizations still inside the window
     */
    VelocitySnapshot snapshot(AccountId account, Currency currency, Instant now);
}
