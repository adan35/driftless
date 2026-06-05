package io.driftless.recon.internal.demo;

import io.driftless.auth.api.AuthorizationStatus;
import io.driftless.auth.internal.RecoverySweep;
import io.driftless.auth.internal.persistence.AuthorizationRepository;
import io.driftless.common.id.AccountId;
import io.driftless.common.money.Money;
import io.driftless.ledger.api.Account;
import io.driftless.ledger.api.AccountType;
import io.driftless.ledger.api.Direction;
import io.driftless.ledger.api.Ledger;
import io.driftless.ledger.api.PostingLine;
import io.driftless.ledger.api.PostingRequest;
import io.driftless.recon.api.DemoResult;
import io.driftless.recon.api.LoadProfile;
import io.driftless.recon.api.LoadResult;
import io.driftless.recon.api.ReconciliationResult;
import io.driftless.recon.internal.ReconciliationService;
import io.driftless.recon.internal.fault.FaultInjectionHarness;
import io.driftless.recon.internal.fault.FaultRequest;
import io.driftless.recon.internal.load.LoadGenerator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Seed-only orchestration behind the Spec 09 demo endpoint. It drives the <em>live</em> system end to
 * end against the composed stack: it seeds funded accounts through the ledger's balanced {@code
 * post()}, injects partner faults through the partner-simulator's control plane, runs the load
 * generator (whose saga calls reach the partner over a real network hop), settles the recovery sweep,
 * then reconciles. The reviewer reads one number: zero drift.
 *
 * <p>It moves no money of its own beyond a normal, balanced funding post and walks only the ordinary
 * authorize / capture / reverse / fault paths, so it cannot corrupt the invariants — a re-run simply
 * appends more correct history and another proof.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoRunner {

    private static final String CURRENCY_CODE = "USD";

    /** An MCC no demo rule blocks (approvals). */
    private static final String APPROVE_MCC = "5411";

    /** An MCC the demo profile's blocking rule declines (deterministic declines). */
    private static final String DECLINE_MCC = "7995";

    private static final int DEFAULT_OPERATIONS = 40;
    private static final int DEFAULT_CONCURRENCY = 8;
    private static final int DEFAULT_ACCOUNTS = 4;
    private static final long DEFAULT_FUNDING_MINOR = 5_000_000L;
    private static final String DEFAULT_FAULT_MIX = "MIXED";
    private static final long DEFAULT_SEED = 42L;

    /** Hard upper bounds — the seed-only demo must never be coaxed into exhausting threads or the DB. */
    private static final int MAX_OPERATIONS = 5_000;

    private static final int MAX_CONCURRENCY = 64;
    private static final int MAX_ACCOUNTS = 1_000;
    private static final long MAX_FUNDING_MINOR = 1_000_000_000_000L;

    /** Bounded budget for draining in-flight authorizations via the recovery sweep. */
    private static final long SWEEP_BUDGET_NANOS = 20_000_000_000L;

    private static final long SWEEP_PAUSE_MILLIS = 150L;

    private final Ledger ledger;
    private final LoadGenerator loadGenerator;
    private final FaultInjectionHarness faultHarness;
    private final RecoverySweep recoverySweep;
    private final AuthorizationRepository authorizations;
    private final ReconciliationService reconciliationService;
    private final Clock clock;

    /**
     * Run the demo. Any {@code null} parameter falls back to a sensible default, so a bare {@code POST
     * /demo/run} produces the full fault story.
     *
     * @param operations authorize operations to drive (default {@value #DEFAULT_OPERATIONS})
     * @param concurrency worker threads issuing them (default {@value #DEFAULT_CONCURRENCY})
     * @param accounts funded accounts to draw against (default {@value #DEFAULT_ACCOUNTS})
     * @param fundingMinor minor units funded into each account (default {@value #DEFAULT_FUNDING_MINOR})
     * @param faultMix the named partner fault mix (default {@value #DEFAULT_FAULT_MIX})
     * @param seed RNG seed making the run reproducible (default {@value #DEFAULT_SEED})
     */
    public DemoResult run(
            Integer operations, Integer concurrency, Integer accounts, Long fundingMinor, String faultMix, Long seed) {
        int ops = orDefault(operations, DEFAULT_OPERATIONS);
        int workers = orDefault(concurrency, DEFAULT_CONCURRENCY);
        int accountCount = orDefault(accounts, DEFAULT_ACCOUNTS);
        long funding = orDefault(fundingMinor, DEFAULT_FUNDING_MINOR);
        String mix = (faultMix == null || faultMix.isBlank()) ? DEFAULT_FAULT_MIX : faultMix;
        long runSeed = orDefault(seed, DEFAULT_SEED);
        require(ops >= 1, "count must be >= 1");
        require(ops <= MAX_OPERATIONS, "count must be <= " + MAX_OPERATIONS);
        require(workers >= 1, "concurrency must be >= 1");
        require(workers <= MAX_CONCURRENCY, "concurrency must be <= " + MAX_CONCURRENCY);
        require(accountCount >= 1, "accounts must be >= 1");
        require(accountCount <= MAX_ACCOUNTS, "accounts must be <= " + MAX_ACCOUNTS);
        require(funding >= 1, "fundingMinor must be >= 1");
        require(funding <= MAX_FUNDING_MINOR, "fundingMinor must be <= " + MAX_FUNDING_MINOR);

        log.info(
                "demo run start ops={} concurrency={} accounts={} fundingMinor={} faultMix={} seed={}",
                ops,
                workers,
                accountCount,
                funding,
                mix,
                runSeed);

        List<AccountId> accountIds = seedFundedAccounts(accountCount, funding);

        List<FaultRequest> plan = DemoFaultPlan.forMix(mix, ops, runSeed);
        List<String> injected = new ArrayList<>(plan.size());
        for (FaultRequest fault : plan) {
            faultHarness.inject(fault);
            injected.add(describe(fault));
        }

        LoadProfile profile = LoadProfile.defaults(ops, workers, runSeed);
        LoadResult load = loadGenerator.run(profile, accountIds, APPROVE_MCC, DECLINE_MCC);

        if (!plan.isEmpty()) {
            faultHarness.reset();
        }

        settleSweep();

        ReconciliationResult reconciliation = reconciliationService.run();
        log.info(
                "demo run done accounts={} faults={} approved={} declined={} compensated={} captures={} reversals={} "
                        + "reconPassed={} driftMinor={}",
                accountCount,
                injected.size(),
                load.approved(),
                load.declined(),
                load.compensated(),
                load.captures(),
                load.reversals(),
                reconciliation.passed(),
                reconciliation.totalDriftMinor());
        return new DemoResult(accountCount, funding, injected, load, reconciliation);
    }

    private List<AccountId> seedFundedAccounts(int count, long fundingMinor) {
        Currency currency = Currency.getInstance(CURRENCY_CODE);
        AccountId settlement = settlementAccountId(currency);
        ledger.openAccount(new Account(settlement, AccountType.ASSET, currency, "settlement-" + CURRENCY_CODE));
        Money amount = Money.of(fundingMinor, currency);
        List<AccountId> accounts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            AccountId cardholder = AccountId.newId();
            ledger.openAccount(new Account(cardholder, AccountType.LIABILITY, currency, "demo-cardholder"));
            ledger.post(new PostingRequest(
                    "demo-fund:" + cardholder,
                    clock.instant(),
                    "demo-funding",
                    List.of(
                            new PostingLine(cardholder, Direction.DEBIT, amount),
                            new PostingLine(settlement, Direction.CREDIT, amount))));
            accounts.add(cardholder);
        }
        log.info("demo seeded {} funded accounts at {} minor each", count, fundingMinor);
        return accounts;
    }

    /** Drain in-flight authorizations the way ops would: re-sweep until none remain or the budget elapses. */
    private void settleSweep() {
        long deadline = System.nanoTime() + SWEEP_BUDGET_NANOS;
        while (System.nanoTime() < deadline) {
            recoverySweep.sweep();
            long inFlight = authorizations.countByStatus(AuthorizationStatus.AUTHORIZING)
                    + authorizations.countByStatus(AuthorizationStatus.COMPENSATING);
            if (inFlight == 0L) {
                return;
            }
            sleep(SWEEP_PAUSE_MILLIS);
        }
        recoverySweep.sweep();
    }

    private static String describe(FaultRequest fault) {
        return "%s@%s%s"
                .formatted(fault.mode(), fault.route(), fault.applyToNext() > 0 ? " x" + fault.applyToNext() : "");
    }

    private static AccountId settlementAccountId(Currency currency) {
        return AccountId.of(UUID.nameUUIDFromBytes(
                ("driftless-settlement-" + currency.getCurrencyCode()).getBytes(StandardCharsets.UTF_8)));
    }

    private static int orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static long orDefault(Long value, long fallback) {
        return value == null ? fallback : value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
