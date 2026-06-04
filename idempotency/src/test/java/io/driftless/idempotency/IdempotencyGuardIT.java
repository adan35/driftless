package io.driftless.idempotency;

import static io.driftless.idempotency.IdempotencyFixtures.account;
import static io.driftless.idempotency.IdempotencyFixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.driftless.common.id.AccountId;
import io.driftless.idempotency.api.IdempotencyConflict;
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
 * Integration tests for {@link IdempotencyGuard} against real Postgres (Testcontainers). Each test
 * maps to a Spec 02 acceptance criterion about the replay guard: replay-after-crash returns the
 * original result and runs no new side effect, and a reused key with a different body is a conflict.
 */
@SpringBootTest
@Import(PostgresTestContainer.class)
class IdempotencyGuardIT {

    static final Instant POSTED_AT = Instant.parse("2026-06-02T00:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(POSTED_AT, ZoneOffset.UTC);
        }
    }

    /** A small serializable result type, to prove typed replay round-trips through the stored blob. */
    record Receipt(String reference, long amountMinor) {}

    @Autowired
    IdempotencyGuard guard;

    @Autowired
    Ledger ledger;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private long ledgerEntryCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM journal_entry", Long.class);
    }

    /** Serialize a value to canonical JSON the way a real caller would before handing it to the guard. */
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- replay after a simulated crash: original result, no new ledger rows ---------------------

    @Test
    void retryAfterCommitReturnsOriginalResultAndRunsNoNewSideEffect() {
        AccountId asset = openUsd(AccountType.ASSET, "settlement");
        AccountId card = openUsd(AccountType.LIABILITY, "cardholder");
        String key = "guard-replay-1";
        String hash = "body-hash-A";
        AtomicInteger operationRuns = new AtomicInteger();

        // First call: the operation posts a real balanced ledger transaction and "commits".
        IdempotentResult<String> first = guard.execute(key, hash, () -> {
            operationRuns.incrementAndGet();
            PostingResult posted = ledger.post(transfer(key, card, asset, 5_00L));
            String txId = posted.transactionId().toString();
            return StoredResult.of(txId, json(txId));
        });
        long entriesAfterFirst = ledgerEntryCount();

        // The process is imagined to die here, before returning to the caller; the caller retries.
        IdempotentResult<String> replay = guard.execute(key, hash, () -> {
            operationRuns.incrementAndGet();
            PostingResult posted = ledger.post(transfer(key, card, asset, 5_00L));
            String txId = posted.transactionId().toString();
            return StoredResult.of(txId, json(txId));
        });

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.value()).isEqualTo(first.value());
        // The operation ran exactly once; the replay produced no new ledger rows.
        assertThat(operationRuns).hasValue(1);
        assertThat(ledgerEntryCount()).isEqualTo(entriesAfterFirst);
    }

    // --- typed replay: a record result round-trips through the response blob ----------------------

    @Test
    void replayReconstructsTheTypedRecordResultFromTheStoredBlob() {
        String key = "guard-typed-1";
        String hash = "body-hash-typed";
        Receipt original = new Receipt("auth-42", 12_34L);
        String receiptJson = json(original);

        IdempotentResult<Receipt> first =
                guard.execute(key, hash, () -> new StoredResult<>(original, receiptJson, Receipt.class));
        IdempotentResult<Receipt> replay = guard.execute(key, hash, () -> {
            throw new AssertionError("operation must not run on replay");
        });

        assertThat(first.replayed()).isFalse();
        assertThat(first.value()).isEqualTo(original);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.value()).isEqualTo(original);
    }

    // --- conflict: same key, different request body ----------------------------------------------

    @Test
    void sameKeyWithDifferentRequestHashIsAConflict() {
        String key = "guard-conflict-1";
        guard.execute(key, "body-hash-original", () -> StoredResult.of("ok", "\"ok\""));

        assertThatThrownBy(() -> guard.execute(key, "body-hash-DIFFERENT", () -> {
                    throw new AssertionError("operation must not run on a conflicting replay");
                }))
                .isInstanceOf(IdempotencyConflict.class)
                .extracting("key", "expectedRequestHash", "actualRequestHash")
                .containsExactly(key, "body-hash-original", "body-hash-DIFFERENT");
    }

    // --- a missing/blank key is rejected before any work ----------------------------------------

    @Test
    void blankKeyIsRejected() {
        assertThatThrownBy(() -> guard.execute("  ", "h", () -> StoredResult.of("x", "\"x\"")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key");
    }

    @Test
    void blankRequestHashIsRejected() {
        assertThatThrownBy(() -> guard.execute("k", "  ", () -> StoredResult.of("x", "\"x\"")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestHash");
    }

    private AccountId openUsd(AccountType type, String name) {
        AccountId id = AccountId.newId();
        ledger.openAccount(account(id, type, name));
        return id;
    }
}
