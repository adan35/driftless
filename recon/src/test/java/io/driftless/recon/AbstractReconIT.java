package io.driftless.recon;

import io.driftless.auth.api.AuthorizationStatus;
import io.driftless.auth.internal.AuthorizationSaga;
import io.driftless.auth.internal.RecoverySweep;
import io.driftless.auth.internal.persistence.AuthorizationRepository;
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
import io.driftless.recon.internal.load.LoadGenerator;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared scaffolding for the recon integration tests: a real Testcontainers Postgres (all five module
 * Flyway histories migrated by {@link ReconMigrations}) and the controllable {@link
 * ControllablePartner} the auth saga's bounded-timeout {@code RestClient} is pointed at. Recon drives
 * the real saga in-process and then reconciles the real committed ledger.
 *
 * <p>The container is a JVM-wide singleton (started once, never explicitly stopped — Ryuk reaps it) so
 * it is shared across the several {@code @SpringBootTest} contexts.
 */
@SpringBootTest(
        classes = {ReconTestApplication.class, ReconMigrations.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractReconIT {

    static final String CURRENCY = "USD";
    static final String APPROVE_MCC = "5411";
    static final String BLOCKED_MCC = "7995";
    static final String MERCHANT = "merchant-1";

    static final ControllablePartner PARTNER = new ControllablePartner();

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    static {
        POSTGRES.start();
    }

    @Autowired
    AuthorizationSaga saga;

    @Autowired
    Ledger ledger;

    @Autowired
    AuthorizationRepository authorizations;

    @Autowired
    HoldRepository holds;

    @Autowired
    RecoverySweep recoverySweep;

    @Autowired
    JournalEntryRepository journalEntries;

    @Autowired
    ReconciliationService reconciliationService;

    @Autowired
    LoadGenerator loadGenerator;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.open-in-view", () -> "false");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.main.allow-bean-definition-overriding", () -> "true");
        // Keep the scheduled relay, recovery sweep and recon job from firing on a wall-clock cadence;
        // tests drive them directly. stuck-after=0 makes a freshly inserted in-flight row sweep-eligible.
        registry.add("driftless.outbox.relay.poll-delay-millis", () -> "3600000");
        registry.add("driftless.recon.schedule-interval", () -> "PT1H");
        // Outbox staleness threshold: tests run fast, so a large window keeps fresh PENDING events from
        // being flagged as stuck (the dedicated outbox test overrides this with a tiny window).
        registry.add("driftless.recon.outbox-stuck-after", () -> "PT1H");
        registry.add("auth.recovery.poll-delay", () -> "PT1H");
        registry.add("auth.recovery.stuck-after", () -> "PT0S");
        // Late-partner grace comfortably exceeds the TIMEOUT sleep so a timeout-then-late-approve is
        // reconciled (the partner reverse is sent) rather than prematurely sealed.
        registry.add("auth.recovery.partner-grace", () -> "PT3S");
        registry.add("auth.partner.base-url", PARTNER::baseUrl);
        registry.add("auth.partner.connect-timeout", () -> "PT0.3S");
        registry.add("auth.partner.read-timeout", () -> "PT0.4S");
        // A blocking MCC rule so declines are deterministic for the load generator's decline mix.
        registry.add("driftless.rules.mcc[0].rule-id", () -> "block-gambling");
        registry.add("driftless.rules.mcc[0].priority", () -> "1");
        registry.add("driftless.rules.mcc[0].mode", () -> "BLOCK");
        registry.add("driftless.rules.mcc[0].codes[0]", () -> BLOCKED_MCC);
    }

    @BeforeEach
    void resetPartner() {
        PARTNER.reset();
    }

    /**
     * Restore the ledger after any deliberately-injected drift. The injection rows are permanent (the
     * append-only trigger forbids DELETE), and the singleton container is shared across every IT, so a
     * test that introduces drift must hand back a balanced ledger — exactly as production would: the
     * correction is a NEW compensating entry, never an edit. Here it is the opposite leg (CREDIT for an
     * injected DEBIT, DEBIT for an injected CREDIT) appended to the same transaction so its legs net to
     * zero again.
     */
    @AfterEach
    void rebalanceInjectedDrift() {
        for (InjectedDrift drift : injectedDrifts) {
            int nextSeq = jdbcTemplate.queryForObject(
                            "SELECT COALESCE(MAX(sequence_no), -1) FROM journal_entry WHERE transaction_id = ?",
                            Integer.class,
                            drift.transactionId())
                    + 1;
            jdbcTemplate.update(
                    "INSERT INTO journal_entry (id, transaction_id, account_id, direction, amount_minor, currency, sequence_no) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(),
                    drift.transactionId(),
                    drift.accountId(),
                    drift.signedDriftMinor() > 0 ? "CREDIT" : "DEBIT",
                    Math.abs(drift.signedDriftMinor()),
                    CURRENCY,
                    nextSeq);
        }
        injectedDrifts.clear();
    }

    private record InjectedDrift(UUID transactionId, UUID accountId, long signedDriftMinor) {}

    private final java.util.List<InjectedDrift> injectedDrifts = new java.util.ArrayList<>();

    // --- ledger helpers --------------------------------------------------------------------------

    /** Open a fresh cardholder account and fund it so it has positive available balance. */
    AccountId openFundedAccount(long fundingMinor) {
        Currency currency = Currency.getInstance(CURRENCY);
        AccountId cardholder = AccountId.newId();
        AccountId settlement = settlementAccountId(currency);
        ledger.openAccount(new Account(cardholder, AccountType.LIABILITY, currency, "cardholder"));
        ledger.openAccount(new Account(settlement, AccountType.ASSET, currency, "settlement-" + CURRENCY));
        Money amount = Money.of(fundingMinor, currency);
        ledger.post(new PostingRequest(
                "fund:" + cardholder,
                Instant.now(),
                "funding",
                List.of(
                        new PostingLine(cardholder, Direction.DEBIT, amount),
                        new PostingLine(settlement, Direction.CREDIT, amount))));
        return cardholder;
    }

    long globalSignedSum() {
        return journalEntries.globalSignedSumMinor(CURRENCY);
    }

    long activeHoldTotal(AccountId account) {
        return holds.activeHoldTotalMinor(account.value(), CURRENCY);
    }

    long available(AccountId account) {
        return ledger.balanceOf(account).available().amountMinor();
    }

    /**
     * Inject artificial drift the way the spec demands: a DELIBERATELY UNBALANCED journal_entry row
     * inserted DIRECTLY VIA SQL, bypassing the ledger's balanced {@code post()} (which would reject
     * it). It reuses a real, existing transaction + account so foreign keys hold; only the balance
     * invariant is violated. Returns the offending transaction id (a real posted transaction).
     */
    UUID injectUnbalancedEntry(AccountId account, long extraDebitMinor) {
        return injectUnbalancedLeg(account, "DEBIT", extraDebitMinor);
    }

    /**
     * Inject a deliberately unbalanced CREDIT leg on the account's transaction. Paired with a DEBIT
     * injection of equal magnitude on a <em>different</em> transaction, the two offset in the global
     * signed sum (so {@code GLOBAL_ZERO} nets to zero) while each transaction is individually
     * unbalanced (so {@code PER_TRANSACTION} still catches both).
     */
    UUID injectUnbalancedCredit(AccountId account, long extraCreditMinor) {
        return injectUnbalancedLeg(account, "CREDIT", extraCreditMinor);
    }

    private UUID injectUnbalancedLeg(AccountId account, String direction, long amountMinor) {
        UUID transactionId = jdbcTemplate.queryForObject(
                "SELECT transaction_id FROM journal_entry WHERE account_id = ? LIMIT 1", UUID.class, account.value());
        Integer maxSeq = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(sequence_no), -1) FROM journal_entry WHERE transaction_id = ?",
                Integer.class,
                transactionId);
        jdbcTemplate.update(
                "INSERT INTO journal_entry (id, transaction_id, account_id, direction, amount_minor, currency, sequence_no) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                transactionId,
                account.value(),
                direction,
                amountMinor,
                CURRENCY,
                maxSeq + 1);
        long signedDrift = "DEBIT".equals(direction) ? amountMinor : -amountMinor;
        injectedDrifts.add(new InjectedDrift(transactionId, account.value(), signedDrift));
        return transactionId;
    }

    /** Drive the recovery sweep to completion: re-sweep until no in-flight authorization remains. */
    void settleSweep() {
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

    static AccountId settlementAccountId(Currency currency) {
        return AccountId.of(UUID.nameUUIDFromBytes(
                ("driftless-settlement-" + currency.getCurrencyCode()).getBytes(StandardCharsets.UTF_8)));
    }

    Money usd(long minor) {
        return Money.of(minor, Currency.getInstance(CURRENCY));
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
