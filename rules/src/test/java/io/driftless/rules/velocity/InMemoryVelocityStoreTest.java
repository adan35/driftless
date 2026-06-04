package io.driftless.rules.velocity;

import static org.assertj.core.api.Assertions.assertThat;

import io.driftless.common.id.AccountId;
import io.driftless.common.money.Money;
import io.driftless.rules.api.VelocitySnapshot;
import io.driftless.rules.config.RuleProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Tests the velocity counter seam: idempotent recording (a retried authorization is counted once,
 * even under concurrency) and correct rolling-window snapshots.
 */
class InMemoryVelocityStoreTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final AccountId ACCOUNT = AccountId.newId();
    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    private static InMemoryVelocityStore storeWithWindow(Duration window) {
        RuleProperties properties = new RuleProperties();
        properties.setVelocityWindow(window);
        return new InMemoryVelocityStore(properties);
    }

    private static Money usd(long minor) {
        return Money.of(minor, USD);
    }

    @Test
    void recordsThenSnapshotsCountAndSum() {
        InMemoryVelocityStore store = storeWithWindow(Duration.ofHours(24));

        store.record("auth-1", ACCOUNT, usd(1_000), NOW.minus(Duration.ofHours(1)));
        store.record("auth-2", ACCOUNT, usd(2_500), NOW.minus(Duration.ofMinutes(30)));

        VelocitySnapshot snapshot = store.snapshot(ACCOUNT, USD, NOW);
        assertThat(snapshot.count()).isEqualTo(2);
        assertThat(snapshot.total()).isEqualTo(usd(3_500));
    }

    @Test
    void retriedAuthorizationIsCountedExactlyOnce() {
        InMemoryVelocityStore store = storeWithWindow(Duration.ofHours(24));

        assertThat(store.record("auth-1", ACCOUNT, usd(1_000), NOW)).isTrue();
        // Same authorization id replayed three times (a retried auth): no double counting.
        assertThat(store.record("auth-1", ACCOUNT, usd(1_000), NOW)).isFalse();
        assertThat(store.record("auth-1", ACCOUNT, usd(1_000), NOW)).isFalse();

        VelocitySnapshot snapshot = store.snapshot(ACCOUNT, USD, NOW);
        assertThat(snapshot.count()).isEqualTo(1);
        assertThat(snapshot.total()).isEqualTo(usd(1_000));
    }

    @Test
    void concurrentReplaysOfTheSameIdCountOnce() throws Exception {
        InMemoryVelocityStore store = storeWithWindow(Duration.ofHours(24));
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> results = pool.invokeAll(IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<Boolean>)
                            () -> store.record("auth-dup", ACCOUNT, usd(1_000), NOW))
                    .toList());

            long winners =
                    results.stream().filter(InMemoryVelocityStoreTest::get).count();
            assertThat(winners).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }

        assertThat(store.snapshot(ACCOUNT, USD, NOW).count()).isEqualTo(1);
    }

    @Test
    void eventsOutsideTheWindowAreExcluded() {
        InMemoryVelocityStore store = storeWithWindow(Duration.ofHours(1));

        store.record("old", ACCOUNT, usd(9_999), NOW.minus(Duration.ofHours(2))); // outside the 1h window
        store.record("recent", ACCOUNT, usd(1_000), NOW.minus(Duration.ofMinutes(10)));

        VelocitySnapshot snapshot = store.snapshot(ACCOUNT, USD, NOW);
        assertThat(snapshot.count()).isEqualTo(1);
        assertThat(snapshot.total()).isEqualTo(usd(1_000));
    }

    @Test
    void unknownAccountSnapshotsEmpty() {
        InMemoryVelocityStore store = storeWithWindow(Duration.ofHours(24));
        VelocitySnapshot snapshot = store.snapshot(AccountId.newId(), USD, NOW);
        assertThat(snapshot.count()).isZero();
        assertThat(snapshot.total()).isEqualTo(usd(0));
    }

    @Test
    void countsAreIsolatedPerAccount() {
        InMemoryVelocityStore store = storeWithWindow(Duration.ofHours(24));
        AccountId other = AccountId.newId();

        store.record("a-1", ACCOUNT, usd(1_000), NOW);
        store.record("b-1", other, usd(5_000), NOW);

        assertThat(store.snapshot(ACCOUNT, USD, NOW).count()).isEqualTo(1);
        assertThat(store.snapshot(ACCOUNT, USD, NOW).total()).isEqualTo(usd(1_000));
        assertThat(store.snapshot(other, USD, NOW).total()).isEqualTo(usd(5_000));
    }

    private static boolean get(Future<Boolean> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
