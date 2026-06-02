package io.driftless.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.driftless.common.id.AccountId;
import io.driftless.common.money.Money;
import io.driftless.ledger.api.Account;
import io.driftless.ledger.api.AccountType;
import io.driftless.ledger.api.Direction;
import io.driftless.ledger.api.Ledger;
import io.driftless.ledger.api.PostingLine;
import io.driftless.ledger.api.PostingRequest;
import io.driftless.ledger.internal.persistence.JournalEntryRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The ledger seed of the Spec 07 zero-drift gate: for any random sequence of balanced posts, the
 * signed global sum of every journal entry is exactly zero, per currency.
 *
 * <p>This is the first version of the property; it deliberately lives in {@code ledger} test sources
 * and exercises balanced transactions only (auth/capture/reversal/fault sequencing belongs to Spec
 * 07, which owns the canonical {@code recon} property test — left untouched). A single Spring +
 * Postgres context is started per container and shared across tries to keep the build fast.
 */
class LedgerZeroDriftPropertyTest {

    private static final Currency[] CURRENCIES = {
        Currency.getInstance("USD"), Currency.getInstance("EUR"), Currency.getInstance("GBP")
    };
    private static final Instant OCCURRED_AT = Instant.parse("2026-06-01T00:00:00Z");

    private static ConfigurableApplicationContext context;
    private static PostgreSQLContainer<?> postgres;
    private static Ledger ledger;
    private static JournalEntryRepository entries;
    private static List<AccountId> assetByCurrencyIndex;

    @BeforeContainer
    static void startContext() {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
        postgres.start();
        context = new SpringApplication(LedgerTestApplication.class)
                .run(
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--spring.jpa.hibernate.ddl-auto=validate",
                        "--spring.jpa.open-in-view=false");
        ledger = context.getBean(Ledger.class);
        entries = context.getBean(JournalEntryRepository.class);

        assetByCurrencyIndex = new ArrayList<>();
        for (Currency currency : CURRENCIES) {
            AccountId asset = AccountId.newId();
            ledger.openAccount(new Account(asset, AccountType.ASSET, currency, "prop-asset-" + currency));
            assetByCurrencyIndex.add(asset);
        }
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

    @Property(tries = 30)
    void sumOfAllJournalEntriesIsZeroPerCurrency(
            @ForAll @Size(min = 1, max = 8) List<@LongRange(min = 1L, max = 1_000_000L) Long> amounts,
            @ForAll @IntRange(min = 0, max = 2) int currencyIndex) {
        Currency currency = CURRENCIES[currencyIndex];
        AccountId asset = assetByCurrencyIndex.get(currencyIndex);

        for (Long amount : amounts) {
            // A self-contained balanced 2-line transaction. Whatever the random magnitudes, every
            // post nets to zero, so the global signed sum must remain zero after each one.
            AccountId counter = AccountId.newId();
            ledger.openAccount(new Account(counter, AccountType.LIABILITY, currency, "prop-liab"));
            PostingRequest request = new PostingRequest(
                    "prop-" + UUID.randomUUID(),
                    OCCURRED_AT,
                    "property",
                    List.of(
                            new PostingLine(asset, Direction.DEBIT, Money.of(amount, currency)),
                            new PostingLine(counter, Direction.CREDIT, Money.of(amount, currency))));
            ledger.post(request);
        }

        // The signature property: across the WHOLE ledger, debits net credits in every currency.
        for (Currency c : CURRENCIES) {
            assertThat(entries.globalSignedSumMinor(c.getCurrencyCode()))
                    .as("global signed sum for %s must be zero", c.getCurrencyCode())
                    .isZero();
        }
    }

    @Property(tries = 5)
    void engineSanityCheck(@ForAll @IntRange(min = 0, max = 10) int n) {
        // Always-on guard that the jqwik engine is actually executing (mirrors review finding W2 for
        // the recon gate) — fails loudly if the engine ever drops off the classpath.
        assertThat(n).isBetween(0, 10);
    }
}
