package io.driftless.recon.internal.load;

import io.driftless.auth.api.AuthorizationView;
import io.driftless.auth.internal.AuthorizationSaga;
import io.driftless.auth.internal.AuthorizeCommand;
import io.driftless.common.id.AccountId;
import io.driftless.common.money.Money;
import io.driftless.recon.api.LoadProfile;
import io.driftless.recon.api.LoadResult;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Drives a realistic, reproducible mix of {@code authorize / capture / reverse} against the auth saga
 * <em>in-process</em> (the deterministic path used by tests and the property gate), at a configurable
 * rate and concurrency, and records throughput + latency.
 *
 * <p>It stresses the system while the fault-injection harness misbehaves the partner, and feeds Spec
 * 08's dashboard. The distributions (approve/decline mix, capture-vs-reverse ratio, amount range) are
 * seedable, so a run is repeatable. Latencies are measured with {@link System#nanoTime()} (a monotonic
 * elapsed-time source, not business time, so no injected {@code Clock} is involved).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoadGenerator {

    private final AuthorizationSaga saga;

    /**
     * Issue {@code profile.operations()} authorizations across {@code accounts}, with declines steered
     * to {@code declineMcc} and approvals to {@code approveMcc}, and capture/reverse follow-ups per the
     * profile's ratios. Blocks until every operation settles, then returns the aggregated metrics.
     *
     * @param profile the traffic shape (counts, concurrency, rate, distributions, seed)
     * @param accounts the funded accounts to draw against (chosen uniformly at random)
     * @param approveMcc an MCC no rule blocks (approvals)
     * @param declineMcc an MCC a configured rule blocks (deterministic declines)
     */
    public LoadResult run(LoadProfile profile, List<AccountId> accounts, String approveMcc, String declineMcc) {
        if (accounts.isEmpty()) {
            throw new IllegalArgumentException("load generator needs at least one funded account");
        }
        log.info(
                "load run start {} accounts={} approveMcc={} declineMcc={}",
                profile,
                accounts.size(),
                approveMcc,
                declineMcc);

        AtomicInteger approved = new AtomicInteger();
        AtomicInteger declined = new AtomicInteger();
        AtomicInteger compensated = new AtomicInteger();
        AtomicInteger captures = new AtomicInteger();
        AtomicInteger reversals = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        ConcurrentLinkedQueue<Long> latenciesMillis = new ConcurrentLinkedQueue<>();
        AtomicLong issued = new AtomicLong();

        long startNanos = System.nanoTime();
        long startWindowNanos = startNanos;
        ExecutorService pool = Executors.newFixedThreadPool(profile.concurrency());
        try {
            Future<?>[] futures = new Future<?>[profile.operations()];
            for (int i = 0; i < profile.operations(); i++) {
                int opIndex = i;
                futures[i] = pool.submit(() -> {
                    throttle(profile, issued.getAndIncrement(), startWindowNanos);
                    runOne(
                            profile,
                            new Random(profile.seed() * 1_000_003L + opIndex),
                            accounts,
                            approveMcc,
                            declineMcc,
                            approved,
                            declined,
                            compensated,
                            captures,
                            reversals,
                            failed,
                            latenciesMillis);
                });
            }
            for (Future<?> future : futures) {
                joinQuietly(future);
            }
        } finally {
            pool.shutdown();
        }
        long elapsedMillis = Math.max(1L, (System.nanoTime() - startNanos) / 1_000_000L);

        List<Long> sorted = latenciesMillis.stream().sorted().toList();
        double throughput = profile.operations() * 1000.0 / elapsedMillis;
        LoadResult result = new LoadResult(
                profile.operations(),
                approved.get(),
                declined.get(),
                compensated.get(),
                captures.get(),
                reversals.get(),
                failed.get(),
                elapsedMillis,
                throughput,
                percentile(sorted, 0.50),
                percentile(sorted, 0.95),
                percentile(sorted, 0.99),
                sorted.isEmpty() ? 0L : sorted.get(sorted.size() - 1));
        log.info(
                "load run done ops={} approved={} declined={} compensated={} captures={} reversals={} failed={} "
                        + "throughput={}/s p99={}ms",
                result.totalOperations(),
                result.approved(),
                result.declined(),
                result.compensated(),
                result.captures(),
                result.reversals(),
                result.failed(),
                String.format("%.1f", result.throughputPerSecond()),
                result.p99LatencyMillis());
        return result;
    }

    private void runOne(
            LoadProfile profile,
            Random random,
            List<AccountId> accounts,
            String approveMcc,
            String declineMcc,
            AtomicInteger approved,
            AtomicInteger declined,
            AtomicInteger compensated,
            AtomicInteger captures,
            AtomicInteger reversals,
            AtomicInteger failed,
            ConcurrentLinkedQueue<Long> latenciesMillis) {
        AccountId account = accounts.get(random.nextInt(accounts.size()));
        boolean steerDecline = random.nextDouble() < profile.declineRatio();
        String mcc = steerDecline ? declineMcc : approveMcc;
        long span = profile.maxAmountMinor() - profile.minAmountMinor() + 1;
        long amountMinor = profile.minAmountMinor() + Math.floorMod(random.nextLong(), span);
        Money amount = Money.of(amountMinor, java.util.Currency.getInstance("USD"));

        long opStart = System.nanoTime();
        try {
            AuthorizationView view = saga.authorize(
                    new AuthorizeCommand(account, amount, mcc, "load-merchant", Optional.empty()), newKey());
            switch (view.status()) {
                case AUTHORIZED -> {
                    approved.incrementAndGet();
                    followUp(profile, random, view, captures, reversals, failed);
                }
                case DECLINED -> declined.incrementAndGet();
                case COMPENSATING, REVERSED -> compensated.incrementAndGet();
                default -> {
                    // CAPTURED is not an authorize outcome; treat any unexpected terminal as approved.
                    approved.incrementAndGet();
                }
            }
        } catch (RuntimeException e) {
            failed.incrementAndGet();
            log.debug("load op failed account={} ({})", account, e.toString());
        } finally {
            latenciesMillis.add((System.nanoTime() - opStart) / 1_000_000L);
        }
    }

    private void followUp(
            LoadProfile profile,
            Random random,
            AuthorizationView authorized,
            AtomicInteger captures,
            AtomicInteger reversals,
            AtomicInteger failed) {
        double roll = random.nextDouble();
        try {
            if (roll < profile.captureRatio()) {
                saga.capture(authorized.id(), Optional.empty(), newKey());
                captures.incrementAndGet();
            } else if (roll < profile.captureRatio() + profile.reverseRatio()) {
                saga.reverse(authorized.id(), newKey());
                reversals.incrementAndGet();
            }
            // else: leave the authorization open (a standing hold) — a realistic outcome.
        } catch (RuntimeException e) {
            failed.incrementAndGet();
            log.debug("load follow-up failed auth={} ({})", authorized.id(), e.toString());
        }
    }

    /** Soft rate limit: if a target rate is set, pace issuance so the achieved rate trends to target. */
    private static void throttle(LoadProfile profile, long alreadyIssued, long startWindowNanos) {
        if (!profile.throttled()) {
            return;
        }
        long targetElapsedNanos = (alreadyIssued * 1_000_000_000L) / profile.targetPerSecond();
        long actualElapsedNanos = System.nanoTime() - startWindowNanos;
        long sleepNanos = targetElapsedNanos - actualElapsedNanos;
        if (sleepNanos > 0) {
            try {
                Thread.sleep(sleepNanos / 1_000_000L, (int) (sleepNanos % 1_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static long percentile(List<Long> sortedAscending, double quantile) {
        if (sortedAscending.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.ceil(quantile * sortedAscending.size()) - 1;
        return sortedAscending.get(Math.max(0, Math.min(index, sortedAscending.size() - 1)));
    }

    private static void joinQuietly(Future<?> future) {
        try {
            future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("load worker failed unexpectedly", e.getCause());
        }
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }
}
