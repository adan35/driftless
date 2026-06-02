package io.driftless.ledger;

import static io.driftless.ledger.LedgerFixtures.OCCURRED_AT;
import static io.driftless.ledger.LedgerFixtures.USD;
import static io.driftless.ledger.LedgerFixtures.account;
import static io.driftless.ledger.LedgerFixtures.credit;
import static io.driftless.ledger.LedgerFixtures.debit;
import static io.driftless.ledger.LedgerFixtures.request;
import static io.driftless.ledger.LedgerFixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.driftless.common.id.AccountId;
import io.driftless.common.id.TxId;
import io.driftless.ledger.api.Account;
import io.driftless.ledger.api.AccountType;
import io.driftless.ledger.api.Balance;
import io.driftless.ledger.api.BalanceInvariantViolation;
import io.driftless.ledger.api.JournalEntry;
import io.driftless.ledger.api.Ledger;
import io.driftless.ledger.api.Page;
import io.driftless.ledger.api.PostingResult;
import io.driftless.ledger.api.Transaction;
import io.driftless.ledger.internal.error.AccountCurrencyMismatch;
import io.driftless.ledger.internal.error.AccountNotFound;
import io.driftless.ledger.internal.error.TransactionNotFound;
import io.driftless.ledger.internal.persistence.JournalEntryRepository;
import io.driftless.ledger.internal.persistence.TransactionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration tests for {@link io.driftless.ledger.internal.LedgerService} against real Postgres
 * (Testcontainers). Each test maps to a Spec 01 acceptance criterion.
 */
@SpringBootTest
@Import(PostgresTestContainer.class)
class LedgerServiceIT {

    /** Fixed clock so {@code postedAt} is deterministic and never reaches for {@code Instant.now()}. */
    static final Instant POSTED_AT = Instant.parse("2026-06-02T00:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(POSTED_AT, ZoneOffset.UTC);
        }
    }

    @Autowired
    Ledger ledger;

    @Autowired
    TransactionRepository transactions;

    @Autowired
    JournalEntryRepository entries;

    @Autowired
    JdbcTemplate jdbc;

    private AccountId openUsdAccount(AccountType type, String name) {
        AccountId id = AccountId.newId();
        ledger.openAccount(account(id, type, USD, name));
        return id;
    }

    // --- openAccount ------------------------------------------------------------------------

    @Test
    void openAccountIsIdempotentOnCallerSuppliedId() {
        AccountId id = AccountId.newId();
        Account first = ledger.openAccount(account(id, AccountType.ASSET, USD, "settlement"));
        Account again = ledger.openAccount(account(id, AccountType.ASSET, USD, "settlement"));

        assertThat(again).isEqualTo(first);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM account WHERE id = ?", Long.class, id.value());
        assertThat(count).isEqualTo(1L);
    }

    // --- post: happy path -------------------------------------------------------------------

    @Test
    void balancedTwoLinePostProducesTwoEntriesAndOneTransaction() {
        AccountId asset = openUsdAccount(AccountType.ASSET, "settlement");
        AccountId liability = openUsdAccount(AccountType.LIABILITY, "cardholder");

        PostingResult result = ledger.post(transfer("post-1", liability, asset, 5_00L, USD));

        assertThat(result.replayed()).isFalse();
        assertThat(result.postedAt()).isEqualTo(POSTED_AT);

        Long txRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transaction WHERE id = ?",
                Long.class,
                result.transactionId().value());
        Long entryRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM journal_entry WHERE transaction_id = ?",
                Long.class,
                result.transactionId().value());
        assertThat(txRows).isEqualTo(1L);
        assertThat(entryRows).isEqualTo(2L);
    }

    // --- post: balance invariant ------------------------------------------------------------

    @Test
    void unbalancedPostIsRejectedAndWritesNothing() {
        AccountId asset = openUsdAccount(AccountType.ASSET, "settlement");
        AccountId liability = openUsdAccount(AccountType.LIABILITY, "cardholder");
        long txBefore = transactions.count();
        long entriesBefore = entries.count();

        // 5.00 debit vs 4.00 credit — unbalanced.
        assertThatThrownBy(() -> ledger.post(
                        request("unbalanced-1", List.of(debit(asset, 5_00L, USD), credit(liability, 4_00L, USD)))))
                .isInstanceOf(BalanceInvariantViolation.class);

        assertThat(transactions.count()).isEqualTo(txBefore);
        assertThat(entries.count()).isEqualTo(entriesBefore);
    }

    // --- post: currency match ---------------------------------------------------------------

    @Test
    void postIsRejectedWhenLineCurrencyDiffersFromAccountCurrency() {
        AccountId usdAccount = openUsdAccount(AccountType.ASSET, "settlement");
        AccountId eurAccount = AccountId.newId();
        ledger.openAccount(account(eurAccount, AccountType.LIABILITY, LedgerFixtures.EUR, "eur-cardholder"));
        long entriesBefore = entries.count();

        // Lines net to zero in their own currencies but the EUR line targets... a EUR account is fine;
        // instead post a USD-denominated line against the EUR account to trip the account-currency check.
        assertThatThrownBy(() -> ledger.post(request(
                        "ccy-mismatch-1", List.of(debit(usdAccount, 1_00L, USD), credit(eurAccount, 1_00L, USD)))))
                .isInstanceOf(AccountCurrencyMismatch.class);

        assertThat(entries.count()).isEqualTo(entriesBefore);
    }

    @Test
    void postIsRejectedWhenAccountDoesNotExist() {
        AccountId known = openUsdAccount(AccountType.ASSET, "settlement");
        AccountId ghost = AccountId.newId();

        assertThatThrownBy(() -> ledger.post(transfer("ghost-1", ghost, known, 1_00L, USD)))
                .isInstanceOf(AccountNotFound.class);
    }

    // --- post: idempotency ------------------------------------------------------------------

    @Test
    void replayWithSameKeyReturnsOriginalTxIdAndWritesNoNewRows() {
        AccountId asset = openUsdAccount(AccountType.ASSET, "settlement");
        AccountId liability = openUsdAccount(AccountType.LIABILITY, "cardholder");

        PostingResult first = ledger.post(transfer("replay-key", liability, asset, 2_50L, USD));
        long txAfterFirst = transactions.count();
        long entriesAfterFirst = entries.count();

        PostingResult replay = ledger.post(transfer("replay-key", liability, asset, 2_50L, USD));

        assertThat(replay.transactionId()).isEqualTo(first.transactionId());
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.postedAt()).isEqualTo(first.postedAt());
        assertThat(transactions.count()).isEqualTo(txAfterFirst);
        assertThat(entries.count()).isEqualTo(entriesAfterFirst);
    }

    // --- balanceOf --------------------------------------------------------------------------

    @Test
    void balanceOfEqualsSummationAndAvailableEqualsPostedWithNoHolds() {
        AccountId asset = openUsdAccount(AccountType.ASSET, "settlement");
        AccountId liability = openUsdAccount(AccountType.LIABILITY, "cardholder");

        ledger.post(transfer("bal-1", liability, asset, 10_00L, USD));
        ledger.post(transfer("bal-2", liability, asset, 4_00L, USD));

        // asset received two debits: +14.00; liability gave two credits: -14.00.
        Balance assetBalance = ledger.balanceOf(asset);
        Balance liabilityBalance = ledger.balanceOf(liability);

        assertThat(assetBalance.posted().amountMinor()).isEqualTo(14_00L);
        assertThat(assetBalance.available()).isEqualTo(assetBalance.posted());
        assertThat(liabilityBalance.posted().amountMinor()).isEqualTo(-14_00L);
        assertThat(liabilityBalance.available()).isEqualTo(liabilityBalance.posted());
    }

    @Test
    void balanceOfUnknownAccountIsRejected() {
        assertThatThrownBy(() -> ledger.balanceOf(AccountId.newId())).isInstanceOf(AccountNotFound.class);
    }

    // --- findTransaction --------------------------------------------------------------------

    @Test
    void findTransactionReturnsTheImmutableViewWithOrderedEntries() {
        AccountId asset = openUsdAccount(AccountType.ASSET, "settlement");
        AccountId liability = openUsdAccount(AccountType.LIABILITY, "cardholder");
        PostingResult posted = ledger.post(transfer("find-1", liability, asset, 7_00L, USD));

        Transaction tx = ledger.findTransaction(posted.transactionId());

        assertThat(tx.id()).isEqualTo(posted.transactionId());
        assertThat(tx.idempotencyKey()).isEqualTo("find-1");
        assertThat(tx.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(tx.postedAt()).isEqualTo(POSTED_AT);
        assertThat(tx.entries()).hasSize(2);
        assertThat(tx.entries()).extracting(JournalEntry::sequenceNo).containsExactly(0, 1);
    }

    @Test
    void findTransactionThrowsTypedExceptionWhenNotFound() {
        assertThatThrownBy(() -> ledger.findTransaction(TxId.newId())).isInstanceOf(TransactionNotFound.class);
    }

    // --- entriesFor -------------------------------------------------------------------------

    @Test
    void entriesForPagesAnAccountsEntriesOldestFirst() {
        AccountId asset = openUsdAccount(AccountType.ASSET, "settlement");
        AccountId liability = openUsdAccount(AccountType.LIABILITY, "cardholder");
        ledger.post(transfer("pg-1", liability, asset, 1_00L, USD));
        ledger.post(transfer("pg-2", liability, asset, 2_00L, USD));
        ledger.post(transfer("pg-3", liability, asset, 3_00L, USD));

        List<JournalEntry> firstPage = ledger.entriesFor(asset, new Page(0, 2));
        List<JournalEntry> secondPage = ledger.entriesFor(asset, new Page(1, 2));

        assertThat(firstPage).hasSize(2);
        assertThat(secondPage).hasSize(1);
        assertThat(firstPage).allSatisfy(e -> assertThat(e.account()).isEqualTo(asset));
    }

    // --- immutability: corrections are new compensating posts -------------------------------

    @Test
    void correctionIsANewCompensatingPostNotAMutation() {
        AccountId asset = openUsdAccount(AccountType.ASSET, "settlement");
        AccountId liability = openUsdAccount(AccountType.LIABILITY, "cardholder");

        ledger.post(transfer("orig", liability, asset, 9_00L, USD));
        // Reverse it with the opposite directions — a brand new balanced transaction.
        ledger.post(request("reversal", List.of(credit(asset, 9_00L, USD), debit(liability, 9_00L, USD))));

        assertThat(ledger.balanceOf(asset).posted().amountMinor()).isZero();
        assertThat(ledger.balanceOf(liability).posted().amountMinor()).isZero();
        // Four immutable rows survive (2 original + 2 compensating); nothing was edited.
        Long entryRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM journal_entry e JOIN account a ON a.id = e.account_id " + "WHERE a.id IN (?, ?)",
                Long.class,
                asset.value(),
                liability.value());
        assertThat(entryRows).isEqualTo(4L);
    }

    // --- append-only enforced at the database -----------------------------------------------

    @Test
    void directUpdateOnJournalEntryIsRejectedByTheTrigger() {
        AccountId asset = openUsdAccount(AccountType.ASSET, "settlement");
        AccountId liability = openUsdAccount(AccountType.LIABILITY, "cardholder");
        ledger.post(transfer("immutable-u", liability, asset, 1_00L, USD));

        assertThatThrownBy(() -> jdbc.update("UPDATE journal_entry SET amount_minor = amount_minor + 1"))
                .hasMessageContaining("append-only");
    }

    @Test
    void directDeleteOnJournalEntryIsRejectedByTheTrigger() {
        AccountId asset = openUsdAccount(AccountType.ASSET, "settlement");
        AccountId liability = openUsdAccount(AccountType.LIABILITY, "cardholder");
        ledger.post(transfer("immutable-d", liability, asset, 1_00L, USD));

        assertThatThrownBy(() -> jdbc.update("DELETE FROM journal_entry")).hasMessageContaining("append-only");
    }
}
