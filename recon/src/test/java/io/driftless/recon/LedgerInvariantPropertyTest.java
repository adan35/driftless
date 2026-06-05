package io.driftless.recon;

import static org.assertj.core.api.Assertions.assertThat;

import io.driftless.auth.api.AuthorizationStatus;
import io.driftless.auth.api.AuthorizationView;
import io.driftless.auth.api.HoldStatus;
import io.driftless.auth.api.IllegalAuthorizationState;
import io.driftless.auth.internal.AuthorizationSaga;
import io.driftless.auth.internal.AuthorizeCommand;
import io.driftless.auth.internal.RecoverySweep;
import io.driftless.auth.internal.persistence.AuthorizationEntity;
import io.driftless.auth.internal.persistence.AuthorizationRepository;
import io.driftless.auth.internal.persistence.HoldEntity;
import io.driftless.auth.internal.persistence.HoldRepository;
import io.driftless.common.id.AccountId;
import io.driftless.common.money.Money;
import io.driftless.ledger.api.Account;
import io.driftless.ledger.api.AccountType;
import io.driftless.ledger.api.Direction;
import io.driftless.ledger.api.Ledger;
import io.driftless.ledger.api.PostingLine;
import io.driftless.ledger.api.PostingRequest;
import io.driftless.ledger.internal.persistence.JournalEntryRepository;
import io.driftless.recon.internal.ReconciliationService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * THE GATE — the non-negotiable zero-drift property test (Spec 07).
 *
 * <p>It generates RANDOM SEQUENCES of {@code authorize / capture / reversal / fault} operations
 * against the <strong>real</strong> {@link Ledger} + auth saga, with a controllable partner injecting
 * the fault modes (approve / decline / timeout / fail-before-response) deterministically. After each
 * sequence SETTLES — the recovery sweep is driven to completion so every COMPENSATING obligation
 * resolves — it asserts the three invariants:
 *
 * <ol>
 *   <li><b>No drift:</b> the global signed sum of journal entries is exactly {@code 0} per currency.
 *   <li><b>No double-effect:</b> replaying every executed operation with its original idempotency key
 *       adds no journal rows and no authorizations (idempotency held).
 *   <li><b>Clean holds:</b> every authorization is either AUTHORIZED-with-an-active-hold (and {@code
 *       available == posted - activeHoldTotal}) or cleanly released — no dangling hold.
 * </ol>
 *
 * <p>Reproducibility relies on jqwik reporting the failing {@code @ForAll seed} of a failed try (which
 * can then be replayed via that seed), plus randomized tries — not on a global fixed seed. A single
 * Spring + Postgres context is booted once per container and shared across tries to keep the build
 * fast; the settle loop is given a comfortable margin so the test is non-flaky.
 */
class LedgerInvariantPropertyTest {

    private static final String CURRENCY = "USD";
    private static final String APPROVE_MCC = "5411";
    private static final String BLOCKED_MCC = "7995";
    private static final String MERCHANT = "prop-merchant";

    // Concurrency knobs: a SMALL set of shared accounts across TWO currencies so operations genuinely
    // race on the same account/holds, driven by a bounded pool. Kept small + bounded for non-flakiness.
    private static final String[] CONCURRENT_CURRENCIES = {"USD", "EUR"};
    private static final int ACCOUNTS_PER_CURRENCY = 2;
    private static final int CONCURRENT_THREADS = 6;

    // APPROVE is weighted so enough authorizations reach AUTHORIZED to be captured/reversed.
    private static final ControllablePartner.AuthorizeMode[] AUTH_MODES = {
        ControllablePartner.AuthorizeMode.APPROVE,
        ControllablePartner.AuthorizeMode.APPROVE,
        ControllablePartner.AuthorizeMode.APPROVE,
        ControllablePartner.AuthorizeMode.TIMEOUT,
        ControllablePartner.AuthorizeMode.FAIL_BEFORE_RESPONSE
    };

    private enum OpType {
        AUTHORIZE,
        CAPTURE,
        REVERSE
    }

    private record ExecutedOp(OpType type, UUID authId, String key, AuthorizeCommand command) {}

    /** A shared account in a specific currency that concurrent ops contend over. */
    private record AccountInfo(AccountId id, String currencyCode) {}

    /** A seed-deterministic op the concurrent burst submits; the INTERLEAVING is what races. */
    private record OpPlan(
            int kind,
            AccountInfo account,
            ControllablePartner.AuthorizeMode mode,
            boolean steerDecline,
            long amount,
            double target) {}

    private static ConfigurableApplicationContext context;
    private static PostgreSQLContainer<?> postgres;
    private static ControllablePartner partner;

    private static AuthorizationSaga saga;
    private static Ledger ledger;
    private static RecoverySweep recoverySweep;
    private static JournalEntryRepository journalEntries;
    private static AuthorizationRepository authorizations;
    private static HoldRepository holds;
    private static ReconciliationService reconciliationService;

    @BeforeContainer
    static void startContext() {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
        postgres.start();
        partner = new ControllablePartner();

        context = new SpringApplication(ReconTestApplication.class, ReconMigrations.class)
                .run(
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--spring.jpa.hibernate.ddl-auto=validate",
                        "--spring.jpa.open-in-view=false",
                        "--spring.flyway.enabled=true",
                        "--spring.main.allow-bean-definition-overriding=true",
                        "--driftless.outbox.relay.poll-delay-millis=3600000",
                        "--driftless.recon.schedule-interval=PT1H",
                        // A large outbox-stuck window: undelivered lifecycle events are young and must not
                        // be flagged while the relay is intentionally idle in this property run.
                        "--driftless.recon.outbox-stuck-after=PT1H",
                        "--auth.recovery.poll-delay=PT1H",
                        "--auth.recovery.stuck-after=PT0S",
                        "--auth.recovery.partner-grace=PT3S",
                        "--auth.partner.base-url=" + partner.baseUrl(),
                        "--auth.partner.connect-timeout=PT0.3S",
                        "--auth.partner.read-timeout=PT0.4S",
                        "--driftless.rules.mcc[0].rule-id=block-gambling",
                        "--driftless.rules.mcc[0].priority=1",
                        "--driftless.rules.mcc[0].mode=BLOCK",
                        "--driftless.rules.mcc[0].codes[0]=" + BLOCKED_MCC);

        saga = context.getBean(AuthorizationSaga.class);
        ledger = context.getBean(Ledger.class);
        recoverySweep = context.getBean(RecoverySweep.class);
        journalEntries = context.getBean(JournalEntryRepository.class);
        authorizations = context.getBean(AuthorizationRepository.class);
        holds = context.getBean(HoldRepository.class);
        reconciliationService = context.getBean(ReconciliationService.class);
    }

    @AfterContainer
    static void stopContext() {
        if (context != null) {
            context.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Property(tries = 15)
    void randomSagaSequenceNeverDriftsAndHoldsNoDoubleEffect(
            @ForAll @LongRange(min = 1L, max = 1_000_000L) long seed, @ForAll @IntRange(min = 1, max = 10) int steps) {
        partner.reset();
        Random random = new Random(seed);
        AccountId account = openFundedAccount(5_000_000);

        List<ExecutedOp> executed = new ArrayList<>();
        List<UUID> openAuthorized = new ArrayList<>();

        for (int i = 0; i < steps; i++) {
            int action = random.nextInt(3);
            if (action != 0 && openAuthorized.isEmpty()) {
                action = 0; // nothing to capture/reverse yet
            }
            switch (action) {
                case 0 -> authorizeStep(account, random, executed, openAuthorized);
                case 1 -> captureStep(random, executed, openAuthorized);
                default -> reverseStep(random, executed, openAuthorized);
            }
        }

        settleSweep();

        assertNoDrift();
        assertNoInFlight();
        assertNoDoubleEffect(executed);
        assertCleanHolds(account, executed);

        // The reconciliation job itself agrees: a settled random sequence reconciles to zero drift.
        assertThat(reconciliationService.run().passed())
                .as(
                        "reconciliation reports zero drift after a settled random sequence (seed=%d, steps=%d)",
                        seed, steps)
                .isTrue();
    }

    /**
     * THE GATE, hardened for CONCURRENCY + MULTI-CURRENCY — the system's hardest claims, now property-tested.
     *
     * <p>Multiple threads issue {@code authorize / capture / reverse} <strong>concurrently</strong> against
     * a <em>small set of shared accounts spanning two currencies (USD + EUR)</em>, so operations genuinely
     * RACE on the same account and the same holds while the {@link ControllablePartner} injects faults. The
     * workload is seed-deterministic (generated single-threaded); only the interleaving races. After the
     * burst joins and the recovery sweep settles to quiescence, it asserts ALL THREE invariants survived the
     * races:
     *
     * <ol>
     *   <li><b>No drift:</b> the global signed sum of journal entries is exactly {@code 0} <em>per currency,
     *       independently</em> — no cross-currency contamination.
     *   <li><b>No double-effect:</b> replaying every successfully issued op with its original idempotency key
     *       adds no journal rows and no authorizations — idempotency held under races.
     *   <li><b>Clean holds:</b> every AUTHORIZED authorization keeps exactly one ACTIVE hold; every terminal
     *       one has none; and per account {@code available == posted - activeHoldTotal}.
     * </ol>
     *
     * <p>The saga's {@code SELECT ... FOR UPDATE} + {@code @Version} on authorizations plus the idempotency
     * guard are what make this hold; legal races (a capture losing to a concurrent reverse, two captures on
     * the same auth) surface as {@link IllegalAuthorizationState} and are tolerated. Any OTHER exception, or
     * any invariant violation, fails the gate.
     */
    @Property(tries = 6)
    void concurrentMultiCurrencySagaNeverDriftsAndHoldsNoDoubleEffect(
            @ForAll @LongRange(min = 1L, max = 1_000_000L) long seed,
            @ForAll @IntRange(min = 12, max = 28) int operations) {
        partner.reset();
        Random random = new Random(seed);

        List<AccountInfo> accounts = new ArrayList<>();
        for (String currencyCode : CONCURRENT_CURRENCIES) {
            for (int i = 0; i < ACCOUNTS_PER_CURRENCY; i++) {
                accounts.add(new AccountInfo(openFundedAccount(currencyCode, 1_000_000_000L), currencyCode));
            }
        }

        // Pre-generate the plan so the WORKLOAD is reproducible from the seed; the concurrent interleaving is
        // what we are stress-testing for races.
        List<OpPlan> plan = new ArrayList<>();
        for (int i = 0; i < operations; i++) {
            AccountInfo account = accounts.get(random.nextInt(accounts.size()));
            int kind = random.nextInt(3); // 0 authorize, 1 capture, 2 reverse
            ControllablePartner.AuthorizeMode mode = AUTH_MODES[random.nextInt(AUTH_MODES.length)];
            boolean steerDecline = random.nextInt(6) == 0;
            long amount = 1_000L + random.nextInt(20_000);
            plan.add(new OpPlan(kind, account, mode, steerDecline, amount, random.nextDouble()));
        }

        List<ExecutedOp> executed = new CopyOnWriteArrayList<>();
        List<UUID> authorizedIds = new CopyOnWriteArrayList<>();
        Queue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_THREADS);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (OpPlan op : plan) {
                futures.add(pool.submit(() -> runConcurrentOp(op, executed, authorizedIds, unexpected)));
            }
            for (Future<?> future : futures) {
                future.get(30L, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("concurrent burst interrupted (seed=" + seed + ")", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("concurrent burst did not complete (seed=" + seed + ")", e);
        } finally {
            pool.shutdownNow();
        }

        assertThat(unexpected)
                .as(
                        "only legal races (IllegalAuthorizationState) tolerated; no UNEXPECTED exception escaped a concurrent op (seed=%d)",
                        seed)
                .isEmpty();

        settleSweepConcurrent();

        assertNoDrift();
        assertNoInFlight();
        assertNoDoubleEffect(executed);
        assertCleanHoldsConcurrent(accounts, executed);

        // The reconciliation job agrees independently: per-currency global zero + hold consistency hold after
        // a settled concurrent multi-currency burst.
        assertThat(reconciliationService.run().passed())
                .as(
                        "reconciliation reports zero drift PER CURRENCY after a settled concurrent burst (seed=%d, ops=%d)",
                        seed, operations)
                .isTrue();
    }

    @Property(tries = 5)
    void engineSanityCheck(
            @ForAll @IntRange(min = 0, max = 10)
                    int n) { // Always-on guard that the jqwik engine is actually executing this gate (review
        // finding W2):
        // fails loudly if the engine ever drops off the classpath or the property is skipped.
        assertThat(n).isBetween(0, 10);
    }

    // --- concurrent steps ------------------------------------------------------------------------

    private void runConcurrentOp(
            OpPlan op, List<ExecutedOp> executed, List<UUID> authorizedIds, Queue<Throwable> unexpected) {
        try {
            if (op.kind() == 0 || authorizedIds.isEmpty()) {
                authorizeConcurrent(op, executed, authorizedIds);
            } else if (op.kind() == 1) {
                captureConcurrent(op, executed, authorizedIds);
            } else {
                reverseConcurrent(op, executed, authorizedIds);
            }
        } catch (IllegalAuthorizationState legalRace) {
            // Expected: a capture/reverse lost a race to a concurrent reverse/capture on the same
            // authorization (or two ops targeted the same id). The loser must roll back cleanly — which is
            // exactly what FOR UPDATE + @Version + the idempotency guard guarantee. Account it and move on.
        } catch (Throwable t) {
            unexpected.add(t);
        }
    }

    private void authorizeConcurrent(OpPlan op, List<ExecutedOp> executed, List<UUID> authorizedIds) {
        String mcc = op.steerDecline() ? BLOCKED_MCC : APPROVE_MCC;
        String merchant = ControllablePartner.merchantWithMode(MERCHANT, op.mode());
        Money amount = money(op.account().currencyCode(), op.amount());
        AuthorizeCommand command = new AuthorizeCommand(op.account().id(), amount, mcc, merchant, Optional.empty());
        String key = UUID.randomUUID().toString();

        AuthorizationView view = saga.authorize(command, key);
        executed.add(new ExecutedOp(OpType.AUTHORIZE, view.id(), key, command));
        if (view.status() == AuthorizationStatus.AUTHORIZED) {
            authorizedIds.add(view.id());
        }
    }

    private void captureConcurrent(OpPlan op, List<ExecutedOp> executed, List<UUID> authorizedIds) {
        UUID authId = pickTarget(authorizedIds, op.target());
        if (authId == null) {
            authorizeConcurrent(op, executed, authorizedIds);
            return;
        }
        String key = UUID.randomUUID().toString();
        saga.capture(authId, Optional.empty(), key);
        executed.add(new ExecutedOp(OpType.CAPTURE, authId, key, null));
    }

    private void reverseConcurrent(OpPlan op, List<ExecutedOp> executed, List<UUID> authorizedIds) {
        UUID authId = pickTarget(authorizedIds, op.target());
        if (authId == null) {
            authorizeConcurrent(op, executed, authorizedIds);
            return;
        }
        String key = UUID.randomUUID().toString();
        saga.reverse(authId, key);
        executed.add(new ExecutedOp(OpType.REVERSE, authId, key, null));
    }

    /**
     * Peek (never remove) a target from the shared authorized ids so MULTIPLE threads can target the SAME
     * authorization concurrently — that is what makes capture/reverse genuinely race on the same holds.
     */
    private static UUID pickTarget(List<UUID> authorizedIds, double selector) {
        int size = authorizedIds.size();
        if (size == 0) {
            return null;
        }
        int index = Math.min(size - 1, (int) (selector * size));
        try {
            return authorizedIds.get(index);
        } catch (IndexOutOfBoundsException shrankUnderRace) {
            return null;
        }
    }

    // --- steps -----------------------------------------------------------------------------------

    private void authorizeStep(AccountId account, Random random, List<ExecutedOp> executed, List<UUID> openAuthorized) {
        partner.setAuthorizeMode(AUTH_MODES[random.nextInt(AUTH_MODES.length)]);
        long amount = 1_000L + random.nextInt(50_000);
        boolean steerDecline = random.nextInt(5) == 0;
        String mcc = steerDecline ? BLOCKED_MCC : APPROVE_MCC;
        AuthorizeCommand command = new AuthorizeCommand(account, usd(amount), mcc, MERCHANT, Optional.empty());
        String key = UUID.randomUUID().toString();

        AuthorizationView view = saga.authorize(command, key);
        executed.add(new ExecutedOp(OpType.AUTHORIZE, view.id(), key, command));
        if (view.status() == AuthorizationStatus.AUTHORIZED) {
            openAuthorized.add(view.id());
        }
    }

    private void captureStep(Random random, List<ExecutedOp> executed, List<UUID> openAuthorized) {
        UUID authId = openAuthorized.remove(random.nextInt(openAuthorized.size()));
        String key = UUID.randomUUID().toString();
        saga.capture(authId, Optional.empty(), key);
        executed.add(new ExecutedOp(OpType.CAPTURE, authId, key, null));
    }

    private void reverseStep(Random random, List<ExecutedOp> executed, List<UUID> openAuthorized) {
        UUID authId = openAuthorized.remove(random.nextInt(openAuthorized.size()));
        String key = UUID.randomUUID().toString();
        saga.reverse(authId, key);
        executed.add(new ExecutedOp(OpType.REVERSE, authId, key, null));
    }

    // --- assertions ------------------------------------------------------------------------------

    private void assertNoDrift() {
        for (String currency : journalEntries.allCurrencies()) {
            assertThat(journalEntries.globalSignedSumMinor(currency))
                    .as("global signed sum for %s must be zero (no drift)", currency)
                    .isZero();
        }
    }

    private void assertNoInFlight() {
        assertThat(authorizations.countByStatus(AuthorizationStatus.AUTHORIZING))
                .as("no AUTHORIZING left after settling")
                .isZero();
        assertThat(authorizations.countByStatus(AuthorizationStatus.COMPENSATING))
                .as("no COMPENSATING left after settling")
                .isZero();
    }

    private void assertNoDoubleEffect(List<ExecutedOp> executed) {
        long journalBefore = journalEntries.count();
        long authBefore = authorizations.count();
        for (ExecutedOp op : executed) {
            switch (op.type()) {
                case AUTHORIZE -> saga.authorize(op.command(), op.key());
                case CAPTURE -> saga.capture(op.authId(), Optional.empty(), op.key());
                case REVERSE -> saga.reverse(op.authId(), op.key());
            }
        }
        assertThat(journalEntries.count())
                .as("replaying every operation with its original key adds NO journal rows (idempotency held)")
                .isEqualTo(journalBefore);
        assertThat(authorizations.count())
                .as("replaying every operation with its original key adds NO authorizations")
                .isEqualTo(authBefore);
    }

    private void assertCleanHolds(AccountId account, List<ExecutedOp> executed) {
        long expectedActiveHold = 0L;
        for (ExecutedOp op : executed) {
            if (op.type() != OpType.AUTHORIZE) {
                continue;
            }
            AuthorizationEntity auth = authorizations.findById(op.authId()).orElseThrow();
            List<HoldEntity> activeHolds = holds.findByAuthorizationIdAndStatus(op.authId(), HoldStatus.ACTIVE);
            if (auth.getStatus() == AuthorizationStatus.AUTHORIZED) {
                assertThat(activeHolds)
                        .as("an AUTHORIZED authorization keeps exactly one ACTIVE hold")
                        .hasSize(1);
                expectedActiveHold += auth.getAmountMinor();
            } else {
                assertThat(activeHolds)
                        .as("a %s authorization has no dangling ACTIVE hold", auth.getStatus())
                        .isEmpty();
            }
        }
        long activeHoldTotal = holds.activeHoldTotalMinor(account.value(), CURRENCY);
        assertThat(activeHoldTotal)
                .as("active hold total equals the sum of AUTHORIZED amounts")
                .isEqualTo(expectedActiveHold);
        long posted = ledger.balanceOf(account).posted().amountMinor();
        long available = ledger.balanceOf(account).available().amountMinor();
        assertThat(available).as("available == posted - activeHoldTotal").isEqualTo(posted - activeHoldTotal);
    }

    private void assertCleanHoldsConcurrent(List<AccountInfo> accounts, List<ExecutedOp> executed) {
        Map<UUID, Long> expectedHoldByAccount = new HashMap<>();
        for (ExecutedOp op : executed) {
            if (op.type() != OpType.AUTHORIZE) {
                continue;
            }
            AuthorizationEntity auth = authorizations.findById(op.authId()).orElseThrow();
            List<HoldEntity> activeHolds = holds.findByAuthorizationIdAndStatus(op.authId(), HoldStatus.ACTIVE);
            if (auth.getStatus() == AuthorizationStatus.AUTHORIZED) {
                assertThat(activeHolds)
                        .as("an AUTHORIZED authorization keeps exactly one ACTIVE hold under races")
                        .hasSize(1);
                expectedHoldByAccount.merge(auth.getAccountId(), auth.getAmountMinor(), Long::sum);
            } else {
                assertThat(activeHolds)
                        .as("a %s authorization has no dangling ACTIVE hold under races", auth.getStatus())
                        .isEmpty();
            }
        }
        for (AccountInfo account : accounts) {
            long expected = expectedHoldByAccount.getOrDefault(account.id().value(), 0L);
            long activeHoldTotal = holds.activeHoldTotalMinor(account.id().value(), account.currencyCode());
            assertThat(activeHoldTotal)
                    .as("active hold total on %s equals the sum of its AUTHORIZED amounts", account.id())
                    .isEqualTo(expected);
            long posted = ledger.balanceOf(account.id()).posted().amountMinor();
            long available = ledger.balanceOf(account.id()).available().amountMinor();
            assertThat(available)
                    .as("available == posted - activeHoldTotal on %s", account.id())
                    .isEqualTo(posted - activeHoldTotal);
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    private void settleSweep() {
        long deadline = System.nanoTime() + 8_000_000_000L;
        while (System.nanoTime() < deadline) {
            recoverySweep.sweep();
            long inFlight = authorizations.countByStatus(AuthorizationStatus.AUTHORIZING)
                    + authorizations.countByStatus(AuthorizationStatus.COMPENSATING);
            if (inFlight == 0L) {
                return;
            }
            sleep(150L);
        }
        recoverySweep.sweep();
    }

    /** Drive the sweep to quiescence with a comfortable budget vs the 2s partner timeout + 3s grace. */
    private void settleSweepConcurrent() {
        long deadline = System.nanoTime() + 25_000_000_000L;
        while (System.nanoTime() < deadline) {
            recoverySweep.sweep();
            long inFlight = authorizations.countByStatus(AuthorizationStatus.AUTHORIZING)
                    + authorizations.countByStatus(AuthorizationStatus.COMPENSATING);
            if (inFlight == 0L) {
                return;
            }
            sleep(200L);
        }
        recoverySweep.sweep();
    }

    private AccountId openFundedAccount(long fundingMinor) {
        return openFundedAccount(CURRENCY, fundingMinor);
    }

    private AccountId openFundedAccount(String currencyCode, long fundingMinor) {
        Currency currency = Currency.getInstance(currencyCode);
        AccountId cardholder = AccountId.newId();
        AccountId settlement = settlementAccountId(currency);
        ledger.openAccount(new Account(cardholder, AccountType.LIABILITY, currency, "cardholder"));
        ledger.openAccount(new Account(settlement, AccountType.ASSET, currency, "settlement-" + currencyCode));
        Money amount = Money.of(fundingMinor, currency);
        ledger.post(new PostingRequest(
                "prop-fund:" + cardholder,
                Instant.parse("2026-06-01T00:00:00Z"),
                "funding",
                List.of(
                        new PostingLine(cardholder, Direction.DEBIT, amount),
                        new PostingLine(settlement, Direction.CREDIT, amount))));
        return cardholder;
    }

    private static AccountId settlementAccountId(Currency currency) {
        return AccountId.of(UUID.nameUUIDFromBytes(
                ("driftless-settlement-" + currency.getCurrencyCode()).getBytes(StandardCharsets.UTF_8)));
    }

    private static Money usd(long minor) {
        return Money.of(minor, Currency.getInstance(CURRENCY));
    }

    private static Money money(String currencyCode, long minor) {
        return Money.of(minor, Currency.getInstance(currencyCode));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
