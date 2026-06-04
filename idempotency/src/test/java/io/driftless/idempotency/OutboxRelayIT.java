package io.driftless.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.driftless.outbox.api.OutboxEvent;
import io.driftless.outbox.api.OutboxWriter;
import io.driftless.outbox.internal.EventPublisher;
import io.driftless.outbox.internal.OutboxRelay;
import io.driftless.outbox.internal.persistence.OutboxEventRepository;
import io.driftless.outbox.internal.persistence.OutboxStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Acceptance tests for the crash-safe outbox relay against real Postgres (Testcontainers):
 *
 * <ul>
 *   <li>Killing the relay between delivery and mark-published re-presents the event on restart;
 *       an idempotent consumer keyed by the event id observes it exactly once (no loss, no
 *       observable duplicate).
 *   <li>The relay preserves per-aggregate ordering (events are observed in append/id order).
 * </ul>
 *
 * <p>{@code batch-size=1} makes each event its own relay transaction, so a forced failure on one
 * event models a crash precisely without coupling unrelated events into the same rollback.
 */
@SpringBootTest(properties = "driftless.outbox.relay.batch-size=1")
@Import(PostgresTestContainer.class)
class OutboxRelayIT {

    static final Instant OCCURRED_AT = Instant.parse("2026-06-02T00:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class RelayTestConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(OCCURRED_AT, ZoneOffset.UTC);
        }

        /**
         * The single {@link EventPublisher} bean for this context: an idempotent-consumer recorder
         * that replaces the default {@code LoggingEventPublisher} (which is {@code
         * ConditionalOnMissingBean}). Declared with its concrete type so it is also injectable as
         * {@link RecordingEventPublisher} for assertions.
         */
        @Bean
        RecordingEventPublisher recordingEventPublisher() {
            return new RecordingEventPublisher();
        }
    }

    @Autowired
    OutboxWriter outbox;

    @Autowired
    OutboxRelay relay;

    @Autowired
    OutboxEventRepository events;

    @Autowired
    RecordingEventPublisher recorder;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    /**
     * Start each test from an empty outbox and a clean recorder so a test observes only its own
     * events. {@code RESTART IDENTITY} resets the id sequence; the assertions key off the ids each
     * test actually appends, so they are independent of execution order.
     */
    @BeforeEach
    void resetOutbox() {
        jdbc.execute("TRUNCATE TABLE outbox_event RESTART IDENTITY");
        recorder.reset();
    }

    // --- crash between deliver and mark: exactly-once observed after restart ---------------------

    @Test
    void eventDeliveredButNotMarkedIsRepresentedAndObservedExactlyOnceAfterRestart() {
        // Two events; arm a crash on the second so it is delivered then its batch rolls back.
        List<Long> ids = appendIdsInOneTransaction("agg-crash", "First", "Second");
        long secondId = ids.get(1);
        recorder.failOnceOn(secondId);

        // First sweep: event 1 commits PUBLISHED; event 2 is delivered to the consumer then its
        // batch fails (rolled back) — modelling a crash after deliver, before mark-published.
        assertThatThrownBy(relay::sweep).isInstanceOf(IllegalStateException.class);
        assertThat(events.countByStatus(OutboxStatus.PENDING)).isEqualTo(1L);

        // Restart: the relay re-presents event 2; the idempotent consumer dedups it by id.
        relay.sweep();

        // No loss: every event ended PUBLISHED.
        assertThat(events.countByStatus(OutboxStatus.PENDING)).isZero();
        // Event 2 was delivered at least twice (proving at-least-once)...
        assertThat(recorder.deliveryAttempts()).filteredOn(id -> id == secondId).hasSizeGreaterThanOrEqualTo(2);
        // ...but observed exactly once by the idempotent consumer, with no duplicate of any id.
        assertThat(recorder.consumed()).containsExactly(ids.get(0), ids.get(1));
    }

    // --- per-aggregate ordering -----------------------------------------------------------------

    @Test
    void relayPreservesPerAggregateOrdering() {
        List<Long> ids = appendIdsInOneTransaction("agg-order", "E0", "E1", "E2", "E3", "E4");

        relay.sweep();

        assertThat(events.countByStatus(OutboxStatus.PENDING)).isZero();
        // The idempotent consumer observed the events in append (id) order.
        assertThat(recorder.consumed()).containsExactlyElementsOf(ids);
    }

    /**
     * Append the events in a single committed transaction and return their persisted ids in order, so
     * the rows are visible (committed) to the relay's own transaction before the sweep runs.
     */
    private List<Long> appendIdsInOneTransaction(String aggregateId, String... eventTypes) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            for (String type : eventTypes) {
                outbox.append(new OutboxEvent(
                        "auth.authorization", aggregateId, type, "{\"a\":\"" + aggregateId + "\"}", OCCURRED_AT));
            }
            return jdbc.queryForList(
                    "SELECT id FROM outbox_event WHERE aggregate_id = ? ORDER BY id ASC", Long.class, aggregateId);
        });
    }
}
