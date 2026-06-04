package io.driftless.idempotency;

import static io.driftless.idempotency.IdempotencyFixtures.account;
import static io.driftless.idempotency.IdempotencyFixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Concurrency acceptance test for {@link IdempotencyGuard}: two requests with the same key race; the
 * unique-constrained idempotency record must let exactly one win and run the side effect, while the
 * other replays the winner's stored result. Both callers receive the same response.
 */
@SpringBootTest
@Import(PostgresTestContainer.class)
class IdempotencyConcurrencyIT {

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

    /** Serialize a value to canonical JSON the way a real caller would before handing it to the guard. */
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void twoConcurrentRequestsWithSameKeyProduceOneSideEffectAndSameResponse() throws Exception {
        AccountId asset = openUsd(AccountType.ASSET, "settlement");
        AccountId card = openUsd(AccountType.LIABILITY, "cardholder");
        String key = "concurrent-key-1";
        String hash = "same-body-hash";
        AtomicInteger sideEffects = new AtomicInteger();
        CyclicBarrier startTogether = new CyclicBarrier(2);

        Callable<IdempotentResult<String>> call = () -> {
            startTogether.await();
            return guard.execute(key, hash, () -> {
                sideEffects.incrementAndGet();
                PostingResult posted = ledger.post(transfer(key, card, asset, 7_00L));
                String txId = posted.transactionId().toString();
                return StoredResult.of(txId, json(txId));
            });
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<IdempotentResult<String>> a = pool.submit(call);
            Future<IdempotentResult<String>> b = pool.submit(call);
            IdempotentResult<String> ra = a.get();
            IdempotentResult<String> rb = b.get();

            // Exactly one side effect: one ledger transaction, and the operation body ran once.
            assertThat(sideEffects).hasValue(1);
            Long txCount = jdbc.queryForObject("SELECT COUNT(*) FROM transaction", Long.class);
            assertThat(txCount).isEqualTo(1L);

            // Both callers got the same response value...
            assertThat(ra.value()).isEqualTo(rb.value());
            // ...and exactly one of them was the fresh execution, the other a replay.
            assertThat(List.of(ra.replayed(), rb.replayed())).containsExactlyInAnyOrder(true, false);

            // Exactly one idempotency record exists for the key.
            Long recordCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM idempotency_record WHERE idempotency_key = ?", Long.class, key);
            assertThat(recordCount).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }

    private AccountId openUsd(AccountType type, String name) {
        AccountId id = AccountId.newId();
        ledger.openAccount(account(id, type, name));
        return id;
    }
}
