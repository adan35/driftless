package io.driftless.tokens;

import static org.assertj.core.api.Assertions.assertThat;

import io.driftless.common.id.TokenId;
import io.driftless.tokens.api.CreateTokenCommand;
import io.driftless.tokens.api.IllegalTokenTransition;
import io.driftless.tokens.api.Token;
import io.driftless.tokens.api.TokenService;
import io.driftless.tokens.api.TokenStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Spec 06 review fix C1 — concurrency regression. Two <em>different</em> transitions on the same token
 * take different idempotency keys, so the guard does not serialize them. Before the fix both
 * transactions read the same stale {@code SUSPENDED} under READ COMMITTED, both passed the edge check,
 * and the second UPDATE overwrote the first — resurrecting a terminal token (ending {@code ACTIVE}
 * after a {@code DEACTIVATE}) and writing two contradictory history rows + events from the same
 * {@code SUSPENDED} origin.
 *
 * <p>The fix loads the token {@code FOR UPDATE} inside the guarded supplier, so the second transaction
 * blocks until the first commits and then re-reads the committed status. This test fires {@code
 * deactivate} and {@code resume} on a {@code SUSPENDED} token from two threads and asserts the
 * serialized, deterministic outcome:
 *
 * <ul>
 *   <li>the terminal state is never exited — the token ends {@code DEACTIVATED};
 *   <li>exactly one new history row (and one event) originates from {@code SUSPENDED} — the two
 *       transactions never both acted on the same stale {@code from} (pre-fix this was two);
 *   <li>{@code deactivate} succeeds and {@code resume} either lost the race and threw {@link
 *       IllegalTokenTransition} (deactivate won) or succeeded first and was then deactivated from
 *       {@code ACTIVE} — never a lost update.
 * </ul>
 */
@SpringBootTest
@Import(PostgresTestContainer.class)
class TokenConcurrencyIT {

    static final Instant NOW = Instant.parse("2026-06-01T10:15:30Z");
    static final String CARD_REF = "card-ref-conc-not-a-pan";

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    TokenService tokens;

    @Autowired
    JdbcTemplate jdbc;

    private long historyRowsFrom(TokenId id, TokenStatus from) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM token_status_history WHERE token_id = ? AND from_status = ?",
                Long.class,
                id.value(),
                from.name());
    }

    private long eventCount(TokenId id) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'TokenStatusChanged'",
                Long.class,
                id.toString());
    }

    private TokenStatus storedStatus(TokenId id) {
        return TokenStatus.valueOf(
                jdbc.queryForObject("SELECT status FROM token WHERE id = ?", String.class, id.value()));
    }

    @Test
    void concurrentDeactivateAndResumeNeverResurrectTheTerminalTokenAndSerialize() throws Exception {
        Token created = tokens.create(CreateTokenCommand.forCard(CARD_REF, TokenId.newId()));
        TokenId id = created.id();
        tokens.activate(id);
        tokens.suspend(id); // SUSPENDED: legal source for both deactivate and resume
        long eventsBefore = eventCount(id);

        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Outcome> deactivate = attempt(startLine, () -> tokens.deactivate(id));
            Callable<Outcome> resume = attempt(startLine, () -> tokens.resume(id));

            Future<Outcome> deactivateFuture = pool.submit(deactivate);
            Future<Outcome> resumeFuture = pool.submit(resume);

            Outcome deactivateOutcome = deactivateFuture.get(30, TimeUnit.SECONDS);
            Outcome resumeOutcome = resumeFuture.get(30, TimeUnit.SECONDS);

            // The terminal state is never exited: deactivate is legal from both SUSPENDED and ACTIVE,
            // so however the two serialize, the token ends DEACTIVATED — never resurrected to ACTIVE.
            assertThat(storedStatus(id)).isEqualTo(TokenStatus.DEACTIVATED);

            // Serialization proof: exactly one transition acted on the stale SUSPENDED origin. Pre-fix
            // both did (two contradictory rows/events from SUSPENDED); the row lock makes it exactly one.
            assertThat(historyRowsFrom(id, TokenStatus.SUSPENDED)).isEqualTo(1);

            // deactivate always eventually succeeds; resume either won the race (then was deactivated
            // from ACTIVE) or lost it and was strictly rejected — never a silent lost update.
            assertThat(deactivateOutcome.succeeded()).isTrue();
            assertThat(deactivateOutcome.token().status()).isEqualTo(TokenStatus.DEACTIVATED);
            if (!resumeOutcome.succeeded()) {
                assertThat(resumeOutcome.error()).isInstanceOf(IllegalTokenTransition.class);
                // resume lost the race: exactly ONE new event was written (the deactivate), not two.
                assertThat(eventCount(id)).isEqualTo(eventsBefore + 1);
            } else {
                assertThat(resumeOutcome.token().status()).isEqualTo(TokenStatus.ACTIVE);
                // resume won, then deactivate ran from ACTIVE: two ordered events, none from a stale read.
                assertThat(eventCount(id)).isEqualTo(eventsBefore + 2);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static Callable<Outcome> attempt(CyclicBarrier startLine, java.util.function.Supplier<Token> op) {
        return () -> {
            startLine.await(10, TimeUnit.SECONDS);
            try {
                return Outcome.ok(op.get());
            } catch (RuntimeException e) {
                return Outcome.failed(e);
            }
        };
    }

    private record Outcome(Token token, RuntimeException error) {
        static Outcome ok(Token token) {
            return new Outcome(token, null);
        }

        static Outcome failed(RuntimeException error) {
            return new Outcome(null, error);
        }

        boolean succeeded() {
            return error == null;
        }
    }
}
