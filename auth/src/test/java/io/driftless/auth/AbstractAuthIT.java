package io.driftless.auth;

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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared scaffolding for the auth integration tests: a real Testcontainers Postgres (all four module
 * Flyway histories migrated by {@link AuthMigrations}) and the controllable {@link
 * ConfigurablePartnerServer} the saga's bounded-timeout {@code RestClient} is pointed at. A blocking
 * MCC rule ({@code 7995}) is configured so the decline path is exercised without affecting the
 * approve path (which uses other MCCs).
 *
 * <p>The container is a JVM-wide singleton (started once in a static initializer, never explicitly
 * stopped — Ryuk reaps it) so it is shared across the several {@code @SpringBootTest} contexts without
 * one test class stopping a container another context still holds.
 */
@SpringBootTest(
        classes = {AuthTestApplication.class, AuthMigrations.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractAuthIT {

    static final String CURRENCY = "USD";
    static final String APPROVE_MCC = "5411";
    static final String BLOCKED_MCC = "7995";
    static final String MERCHANT = "merchant-1";

    static final ConfigurablePartnerServer PARTNER = new ConfigurablePartnerServer();

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

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.open-in-view", () -> "false");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.main.allow-bean-definition-overriding", () -> "true");
        // Keep the scheduled relay and recovery sweep from firing on a wall-clock cadence; tests drive
        // them directly. stuck-after=0 makes a freshly inserted in-flight row immediately sweep-eligible.
        registry.add("driftless.outbox.relay.poll-delay-millis", () -> "3600000");
        registry.add("auth.recovery.poll-delay", () -> "PT1H");
        registry.add("auth.recovery.stuck-after", () -> "PT0S");
        // Late-partner grace: longer than the read timeout so a timeout-then-late-approve is reconciled
        // by the sweep rather than prematurely sealed, yet short enough to keep the tests brisk.
        registry.add("auth.recovery.partner-grace", () -> "PT2S");
        // Bounded partner timeout small enough that TIMEOUT mode (sleeps 2s) breaches it.
        registry.add("auth.partner.base-url", PARTNER::baseUrl);
        registry.add("auth.partner.connect-timeout", () -> "PT0.3S");
        registry.add("auth.partner.read-timeout", () -> "PT0.4S");
        // A blocking MCC rule so the rule-engine decline path is testable in isolation.
        registry.add("driftless.rules.mcc[0].rule-id", () -> "block-gambling");
        registry.add("driftless.rules.mcc[0].priority", () -> "1");
        registry.add("driftless.rules.mcc[0].mode", () -> "BLOCK");
        registry.add("driftless.rules.mcc[0].codes[0]", () -> BLOCKED_MCC);
    }

    @BeforeEach
    void resetPartner() {
        PARTNER.reset();
    }

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

    long posted(AccountId account) {
        return ledger.balanceOf(account).posted().amountMinor();
    }

    long available(AccountId account) {
        return ledger.balanceOf(account).available().amountMinor();
    }

    long activeHoldTotal(AccountId account) {
        return holds.activeHoldTotalMinor(account.value(), CURRENCY);
    }

    /** The zero-drift invariant: the signed sum of every journal entry in the currency is exactly 0. */
    void assertZeroDrift(AccountId cardholder) {
        long globalSum = journalEntries.globalSignedSumMinor(CURRENCY);
        if (globalSum != 0L) {
            throw new AssertionError("ledger drift detected: global signed sum = " + globalSum);
        }
    }

    static AccountId settlementAccountId(Currency currency) {
        return AccountId.of(UUID.nameUUIDFromBytes(
                ("driftless-settlement-" + currency.getCurrencyCode()).getBytes(StandardCharsets.UTF_8)));
    }

    Money usd(long minor) {
        return Money.of(minor, Currency.getInstance(CURRENCY));
    }
}
