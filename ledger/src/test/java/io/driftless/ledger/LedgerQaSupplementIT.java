package io.driftless.ledger;

import static io.driftless.ledger.LedgerFixtures.USD;
import static io.driftless.ledger.LedgerFixtures.account;
import static io.driftless.ledger.LedgerFixtures.credit;
import static io.driftless.ledger.LedgerFixtures.debit;
import static io.driftless.ledger.LedgerFixtures.request;
import static io.driftless.ledger.LedgerFixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.driftless.common.id.AccountId;
import io.driftless.ledger.api.AccountType;
import io.driftless.ledger.api.PostingResult;
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
 * QA-authored supplement to {@link LedgerServiceIT}: boundary / negative cases the developer did not
 * cover, plus an explicit probe of the Spec-01-vs-Spec-02 idempotency boundary (a used key with a
 * different body). These ASSERT real behaviour against Testcontainers Postgres; nothing here changes
 * production source or the frozen contract.
 */
@SpringBootTest
@Import(PostgresTestContainer.class)
class LedgerQaSupplementIT {

    static final Instant POSTED_AT = Instant.parse("2026-06-02T00:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(POSTED_AT, ZoneOffset.UTC);
        }
    }

    @Autowired
    io.driftless.ledger.api.Ledger ledger;

    @Autowired
    TransactionRepository transactions;

    @Autowired
    JournalEntryRepository entries;

    @Autowired
    JdbcTemplate jdbc;

    private AccountId openUsd(AccountType type, String name) {
        AccountId id = AccountId.newId();
        ledger.openAccount(account(id, type, USD, name));
        return id;
    }

    // --- boundary: a balanced post with MORE THAN two legs is accepted with the right row count ---

    @Test
    void balancedFourLinePostProducesFourEntries() {
        AccountId asset = openUsd(AccountType.ASSET, "settlement");
        AccountId fee = openUsd(AccountType.REVENUE, "fee-income");
        AccountId card = openUsd(AccountType.LIABILITY, "cardholder");

        // card pays 10.00 (credit). asset receives 9.50 (debit), fee receives 0.50 (debit).
        // Two debits + ... we need debits==credits: credit card 10.00; debit asset 9.50, debit fee 0.50.
        // That is 3 legs and already balanced; add a 4th balanced pair to exercise >3 legs.
        PostingResult result = ledger.post(request(
                "multi-4",
                List.of(
                        debit(asset, 9_50L, USD),
                        debit(fee, 50L, USD),
                        credit(card, 10_00L, USD),
                        // a self-cancelling extra pair keeps the batch balanced and gives 4+ legs
                        debit(card, 1_00L, USD),
                        credit(asset, 1_00L, USD))));

        Long entryRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM journal_entry WHERE transaction_id = ?",
                Long.class,
                result.transactionId().value());
        assertThat(entryRows).isEqualTo(5L);
        // Sequence numbers are dense 0..4 in insertion order.
        List<Integer> seq = jdbc.queryForList(
                "SELECT sequence_no FROM journal_entry WHERE transaction_id = ? ORDER BY sequence_no",
                Integer.class,
                result.transactionId().value());
        assertThat(seq).containsExactly(0, 1, 2, 3, 4);
        // Net effect is still zero across the whole batch.
        assertThat(ledger.balanceOf(asset).posted().amountMinor()).isEqualTo(8_50L);
        assertThat(ledger.balanceOf(fee).posted().amountMinor()).isEqualTo(50L);
        assertThat(ledger.balanceOf(card).posted().amountMinor()).isEqualTo(-9_00L);
    }

    // --- boundary: a single-leg request is rejected by the frozen record guard, nothing written ---

    @Test
    void singleLineRequestIsRejectedBeforeAnyWrite() {
        AccountId asset = openUsd(AccountType.ASSET, "settlement");
        long txBefore = transactions.count();
        long entriesBefore = entries.count();

        assertThatThrownBy(() -> request("one-leg", List.of(debit(asset, 1_00L, USD))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 2 lines");

        assertThat(transactions.count()).isEqualTo(txBefore);
        assertThat(entries.count()).isEqualTo(entriesBefore);
    }

    // --- RISK PROBE: a USED idempotency key with a DIFFERENT body. Spec 01 honours the key and
    // returns the ORIGINAL result, silently ignoring the new (conflicting) body. Enforcing
    // 409-on-different-body is Spec 02's REST concern; this test PINS the current ledger behaviour
    // so the risk is documented, not a regression. ---

    @Test
    void usedKeyWithDifferentBodyReturnsOriginalAndIgnoresNewBody() {
        AccountId asset = openUsd(AccountType.ASSET, "settlement");
        AccountId card = openUsd(AccountType.LIABILITY, "cardholder");

        PostingResult original = ledger.post(transfer("reuse-key", card, asset, 5_00L, USD));
        long entriesAfterFirst = entries.count();

        // Same key, DIFFERENT amount (8.00 instead of 5.00) and different description.
        PostingResult replay =
                ledger.post(request("reuse-key", List.of(debit(asset, 8_00L, USD), credit(card, 8_00L, USD))));

        // The ledger returned the ORIGINAL transaction, marked replayed; the new body was NOT applied.
        assertThat(replay.transactionId()).isEqualTo(original.transactionId());
        assertThat(replay.replayed()).isTrue();
        assertThat(entries.count()).isEqualTo(entriesAfterFirst);
        // The balance reflects the ORIGINAL 5.00, proving the conflicting 8.00 body was ignored.
        assertThat(ledger.balanceOf(asset).posted().amountMinor()).isEqualTo(5_00L);
    }

    // --- boundary: balance is computed by signed summation across many posts (no stored balance) ---

    @Test
    void postedBalanceIsTheSignedSumOfManyEntries() {
        AccountId asset = openUsd(AccountType.ASSET, "settlement");
        AccountId card = openUsd(AccountType.LIABILITY, "cardholder");

        ledger.post(transfer("sum-1", card, asset, 3_00L, USD));
        ledger.post(transfer("sum-2", card, asset, 7_00L, USD));
        // a reversing post (asset credited) reduces the asset balance
        ledger.post(request("sum-3", List.of(credit(asset, 2_00L, USD), debit(card, 2_00L, USD))));

        // asset: +3 +7 -2 = +8.00 ; card is the mirror: -8.00
        assertThat(ledger.balanceOf(asset).posted().amountMinor()).isEqualTo(8_00L);
        assertThat(ledger.balanceOf(card).posted().amountMinor()).isEqualTo(-8_00L);
        // And the cross-account signed sum is exactly zero (the invariant, at account granularity).
        long assetSum = ledger.balanceOf(asset).posted().amountMinor();
        long cardSum = ledger.balanceOf(card).posted().amountMinor();
        assertThat(assetSum + cardSum).isZero();
    }

    // --- negative: empty account list cannot even be constructed (frozen guard), nothing written ---

    @Test
    void emptyLineListIsRejectedByTheRecordGuard() {
        long entriesBefore = entries.count();
        assertThatThrownBy(() -> request("empty", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 2 lines");
        assertThat(entries.count()).isEqualTo(entriesBefore);
    }
}
