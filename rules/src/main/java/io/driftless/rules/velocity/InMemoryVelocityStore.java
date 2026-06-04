package io.driftless.rules.velocity;

import io.driftless.common.id.AccountId;
import io.driftless.common.money.Money;
import io.driftless.rules.api.VelocitySnapshot;
import io.driftless.rules.config.RuleProperties;
import java.time.Instant;
import java.util.Currency;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * In-memory {@link VelocityStore}: the MVP backing for velocity counters, with idempotent recording
 * and rolling-window snapshots that never touch a database.
 *
 * <p><b>Idempotency.</b> A global {@link ConcurrentHashMap} of seen authorization ids gates recording
 * via {@code putIfAbsent}: the first call for an id wins and appends an event; any later call with the
 * same id (a retried authorization) returns {@code false} and changes no counter. This is the seam
 * Spec 03 leans on so a replayed auth is counted exactly once.
 *
 * <p><b>Window.</b> Events are held per account in a time-ordered deque and pruned lazily — on each
 * record and snapshot, entries older than {@code now - velocityWindow} are dropped from the front.
 * {@link #snapshot} then counts and sums the survivors, so the answer is an in-memory scan of the
 * recent window only, not a {@code GROUP BY}.
 *
 * <p><b>Concurrency.</b> Per-account deques are {@link ConcurrentLinkedDeque}; appends are lock-free.
 * A snapshot taken concurrently with a record observes a consistent prefix of events — acceptable for
 * a velocity estimate, and the idempotency gate guarantees no event is ever counted twice.
 *
 * <p>This is deliberately a clean seam: a Redis/persistence implementation can replace it behind
 * {@link VelocityStore} with no change to the engine or the saga. The in-memory store is suitable for
 * a single-node deployment; a distributed deployment swaps in the persistent variant.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InMemoryVelocityStore implements VelocityStore {

    private final RuleProperties properties;

    /** Global dedup set of authorization ids already recorded — the idempotency gate. */
    private final Map<String, Boolean> recordedAuthorizations = new ConcurrentHashMap<>();

    /** Per-account, time-ordered events still potentially inside the window. */
    private final Map<AccountId, Deque<VelocityEvent>> events = new ConcurrentHashMap<>();

    @Override
    public boolean record(String authorizationId, AccountId account, Money amount, Instant occurredAt) {
        if (recordedAuthorizations.putIfAbsent(authorizationId, Boolean.TRUE) != null) {
            log.debug("velocity record skipped: authorization {} already counted", authorizationId);
            return false;
        }
        Deque<VelocityEvent> accountEvents = events.computeIfAbsent(account, ignored -> new ConcurrentLinkedDeque<>());
        accountEvents.addLast(new VelocityEvent(amount.amountMinor(), occurredAt));
        pruneExpired(accountEvents, windowStart(occurredAt));
        log.debug("velocity recorded authorization {} account={} amount={}", authorizationId, account, amount);
        return true;
    }

    @Override
    public VelocitySnapshot snapshot(AccountId account, Currency currency, Instant now) {
        Deque<VelocityEvent> accountEvents = events.get(account);
        if (accountEvents == null) {
            return VelocitySnapshot.empty(Money.zero(currency));
        }
        Instant windowStart = windowStart(now);
        pruneExpired(accountEvents, windowStart);

        int count = 0;
        long totalMinor = 0L;
        for (VelocityEvent event : accountEvents) {
            if (!event.occurredAt().isBefore(windowStart) && !event.occurredAt().isAfter(now)) {
                count++;
                totalMinor = Math.addExact(totalMinor, event.amountMinor());
            }
        }
        return new VelocitySnapshot(count, Money.of(totalMinor, currency));
    }

    private Instant windowStart(Instant reference) {
        return reference.minus(properties.getVelocityWindow());
    }

    /**
     * Drop events strictly older than {@code windowStart} from the front of the (broadly time-ordered)
     * deque. This is a memory optimization only — {@link #snapshot} re-checks each survivor against the
     * exact window bounds, so a prune that stops early under out-of-order concurrent appends can never
     * make a snapshot count an expired event.
     */
    private static void pruneExpired(Deque<VelocityEvent> accountEvents, Instant windowStart) {
        Iterator<VelocityEvent> it = accountEvents.iterator();
        while (it.hasNext()) {
            if (it.next().occurredAt().isBefore(windowStart)) {
                it.remove();
            } else {
                return;
            }
        }
    }

    /** One recorded authorization: its amount (minor units) and the business time it occurred. */
    private record VelocityEvent(long amountMinor, Instant occurredAt) {}
}
