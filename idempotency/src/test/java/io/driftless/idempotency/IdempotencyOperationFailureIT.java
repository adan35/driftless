package io.driftless.idempotency;

import static io.driftless.idempotency.IdempotencyFixtures.account;
import static io.driftless.idempotency.IdempotencyFixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.driftless.common.id.AccountId;
import io.driftless.idempotency.api.IdempotencyGuard;
import io.driftless.idempotency.api.IdempotentResult;
import io.driftless.idempotency.api.StoredResult;
import io.driftless.ledger.api.AccountType;
import io.driftless.ledger.api.Ledger;
import io.driftless.ledger.api.PostingResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * QA edge-case probe (Spec 02): a guarded operation that <em>throws</em> must not poison the key. The
 * claim is inserted in the same transaction as the operation, so a thrown operation has to roll the
 * claim back -- leaving the key free to be retried -- rather than persisting an {@code IN_PROGRESS}
 * tombstone (which would wedge the key forever) or, worse, a phantom {@code COMPLETED} with no real
 * side effect (which would make a real retry replay a success that never happened).
 *
 * <p>This is the crash-safety counterpart to the happy-path replay test: the saga's bounded-timeout
 * compensation depends on a failed step leaving the ledger AND the idempotency store clean.
 */
@SpringBootTest
@Import(PostgresTestContainer.class)
class IdempotencyOperationFailureIT {

    static final Instant POSTED_AT = Instant.parse("2026-06-02T00:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(POSTED_AT, ZoneOffset.UTC);
        }
    }

    @Autowired
    IdempotencyGuard guard;

    @Autowired
    Ledger ledger;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private long recordCount(String key) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE idempotency_key = ?", Long.class, key);
    }

    private long ledgerEntryCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM journal_entry", Long.class);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The operation throws after posting a ledger transaction in the same transaction. The exception
     * propagates, the transaction rolls back, and NEITHER the ledger rows NOR the idempotency claim
     * survive -- so the key is unused and retryable.
     */
    @Test
    void aThrownOperationRollsBackTheClaimAndTheLedgerLeavingTheKeyRetryable() {
        AccountId asset = openUsd(AccountType.ASSET, "settlement");
        AccountId card = openUsd(AccountType.LIABILITY, "cardholder");
        String key = "op-fails-1";
        String hash = "body-hash";
        long entriesBefore = ledgerEntryCount();

        assertThatThrownBy(() -> guard.execute(key, hash, () -> {
                    ledger.post(transfer(key, card, asset, 5_00L));
                    throw new IllegalStateException("business failure after the ledger post");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("business failure");

        // No claim row lingers (no IN_PROGRESS tombstone), and no ledger rows were committed.
        assertThat(recordCount(key)).isZero();
        assertThat(ledgerEntryCount()).isEqualTo(entriesBefore);
    }

    /**
     * Because the failed attempt left no record, the SAME key can be retried and now succeeds exactly
     * once -- proving the failure did not wedge the key and did not pre-bank a phantom result.
     */
    @Test
    void afterAFailedOperationTheSameKeySucceedsExactlyOnceOnRetry() {
        AccountId asset = openUsd(AccountType.ASSET, "settlement");
        AccountId card = openUsd(AccountType.LIABILITY, "cardholder");
        String key = "op-fails-then-succeeds";
        String hash = "body-hash";
        AtomicInteger sideEffects = new AtomicInteger();

        // First attempt blows up after attempting the post.
        assertThatThrownBy(() -> guard.execute(key, hash, () -> {
                    sideEffects.incrementAndGet();
                    ledger.post(transfer(key, card, asset, 5_00L));
                    throw new IllegalStateException("transient failure");
                }))
                .isInstanceOf(IllegalStateException.class);
        long entriesAfterFailure = ledgerEntryCount();

        // Retry with the same key succeeds and is treated as a fresh execution (not a replay), because
        // the poisoned attempt left nothing behind.
        IdempotentResult<String> retry = guard.execute(key, hash, () -> {
            sideEffects.incrementAndGet();
            PostingResult posted = ledger.post(transfer(key, card, asset, 5_00L));
            String txId = posted.transactionId().toString();
            return StoredResult.of(txId, json(txId));
        });

        assertThat(retry.replayed()).isFalse();
        assertThat(retry.value()).isNotBlank();
        // The successful retry committed exactly one transaction's worth of entries (2 lines).
        assertThat(ledgerEntryCount()).isEqualTo(entriesAfterFailure + 2);
        // The operation body ran twice (once failed, once succeeded) -- no silent skip on the retry.
        assertThat(sideEffects).hasValue(2);

        // And a subsequent replay of the now-committed key returns the stored result with no new rows.
        long entriesAfterSuccess = ledgerEntryCount();
        IdempotentResult<String> replay = guard.execute(key, hash, () -> {
            throw new AssertionError("operation must not run on replay of a committed key");
        });
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.value()).isEqualTo(retry.value());
        assertThat(ledgerEntryCount()).isEqualTo(entriesAfterSuccess);
    }

    private AccountId openUsd(AccountType type, String name) {
        AccountId id = AccountId.newId();
        ledger.openAccount(account(id, type, name));
        return id;
    }
}
