package io.driftless.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.driftless.outbox.api.OutboxEvent;
import io.driftless.outbox.api.OutboxWriter;
import io.driftless.outbox.internal.EventPublisher;
import io.driftless.outbox.internal.OutboxRelay;
import io.driftless.outbox.internal.PublishedOutboxEvent;
import io.driftless.outbox.internal.persistence.OutboxEventRepository;
import io.driftless.outbox.internal.persistence.OutboxStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * QA edge-case probe (Spec 02): what happens when a publish fails <em>partway through a multi-event
 * batch</em>. This is the risk the orchestrator flagged -- could the relay deliver a later event ahead
 * of an earlier one, or mishandle the {@code attempts}/{@code status} accounting in a way that
 * double-publishes to a NON-idempotent consumer?
 *
 * <p>The relay design aborts the whole batch on a publish failure (a single {@code @Transactional}
 * method, so the rollback covers every row touched). These tests pin that contract down with
 * {@code batch-size > 1}:
 *
 * <ul>
 *   <li>An earlier event already handed to the consumer in the same batch is rolled back to {@code
 *       PENDING} (not left {@code PUBLISHED}) when a later event in the batch fails.
 *   <li>On the retry sweep, ordering is still preserved and nothing is lost.
 *   <li><b>Documented hazard:</b> the earlier event IS re-delivered to the publisher (at-least-once),
 *       so a non-idempotent consumer would see it twice. This is the exact "must dedup by event id"
 *       contract -- proven here, not assumed.
 * </ul>
 */
@SpringBootTest(properties = "driftless.outbox.relay.batch-size=10")
@Import(PostgresTestContainer.class)
class OutboxBatchFailureIT {

    static final Instant OCCURRED_AT = Instant.parse("2026-06-02T00:00:00Z");

    /**
     * A publisher that records every delivery and can be armed to throw on the first delivery of a
     * given id. Unlike the shared {@code RecordingEventPublisher}, it does NOT dedup -- it is the raw
     * at-least-once delivery stream, so a test can observe whether an earlier batch member is
     * re-presented after a later member fails (the double-publish-to-non-idempotent-consumer hazard).
     */
    static final class BatchRecordingPublisher implements EventPublisher {
        private final List<Long> deliveries = new CopyOnWriteArrayList<>();
        private volatile long failOnceId = Long.MIN_VALUE;

        @Override
        public synchronized void publish(PublishedOutboxEvent event) {
            deliveries.add(event.id());
            if (event.id() == failOnceId) {
                failOnceId = Long.MIN_VALUE;
                throw new IllegalStateException("simulated publish failure on event " + event.id());
            }
        }

        void failOnceOn(long id) {
            this.failOnceId = id;
        }

        List<Long> deliveries() {
            return List.copyOf(deliveries);
        }

        void reset() {
            deliveries.clear();
            failOnceId = Long.MIN_VALUE;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        Clock clock() {
            return Clock.fixed(OCCURRED_AT, ZoneOffset.UTC);
        }

        @Bean
        BatchRecordingPublisher batchRecordingPublisher() {
            return new BatchRecordingPublisher();
        }
    }

    @Autowired
    OutboxWriter outbox;

    @Autowired
    OutboxRelay relay;

    @Autowired
    OutboxEventRepository events;

    @Autowired
    BatchRecordingPublisher publisher;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE TABLE outbox_event RESTART IDENTITY");
        publisher.reset();
    }

    /**
     * Three events land in ONE batch (batch-size=10). The publish of the middle event fails. Because
     * the batch is one transaction, the whole batch rolls back: NO row is left PUBLISHED -- including
     * the first, which the consumer already received. Ordering can therefore never invert, but the
     * first event WILL be re-presented on retry (so the consumer must dedup by id).
     */
    @Test
    void aMidBatchPublishFailureRollsBackTheWholeBatchSoNoEarlierEventIsLeftPublished() {
        List<Long> ids = appendInOneTransaction("agg-batch", "E0", "E1", "E2");
        long middle = ids.get(1);
        publisher.failOnceOn(middle);

        // The sweep throws (the batch transaction aborts).
        assertThatThrownBy(relay::sweep).isInstanceOf(IllegalStateException.class);

        // CRITICAL (verified): nothing is half-committed. All three rows are still PENDING despite E0
        // having been delivered to the consumer in the failed batch -- the rollback undid its
        // mark-published AND its recordAttempt() (attempts back to 0). This is the crash-safety
        // guarantee: a publish failure leaves the whole batch exactly as it was.
        assertThat(events.countByStatus(OutboxStatus.PENDING)).isEqualTo(3L);
        assertThat(events.countByStatus(OutboxStatus.PUBLISHED)).isZero();

        // The first batch delivered E0 then E1 (which failed); E2 was never reached.
        assertThat(publisher.deliveries()).containsExactly(ids.get(0), middle);

        // Retry: now everything lands.
        relay.sweep();
        assertThat(events.countByStatus(OutboxStatus.PENDING)).isZero();

        // ORDERING (the orchestrator's risk): a later event is never delivered AHEAD of an earlier one.
        // Because the failed batch rolled back wholesale and the relay re-claims from the lowest pending
        // id, the retry replays the batch from its start: the full at-least-once stream is
        // [E0, E1, E0, E1, E2]. The relevant safety property is that within each contiguous sweep ids are
        // ascending, and the FIRST (committing) occurrence of each id is in ascending order -- so an
        // id-keyed idempotent consumer applies E0 < E1 < E2 in order and never inverts them.
        assertThat(firstOccurrenceOrder(publisher.deliveries()))
                .as("the de-duplicated (first-seen) delivery order must be ascending by id")
                .containsExactly(ids.get(0), ids.get(1), ids.get(2));

        // Documented at-least-once hazard the orchestrator asked about: E0 (an EARLIER event than the
        // one that failed) is delivered TWICE -- once in the rolled-back batch, once on retry. A
        // NON-idempotent consumer would double-apply E0. This is exactly why Spec 02 mandates an
        // id-keyed idempotent consumer; the redelivery itself is correct (at-least-once), not a bug.
        assertThat(publisher.deliveries()).filteredOn(id -> id == ids.get(0)).hasSize(2);
        assertThat(publisher.deliveries()).containsSubsequence(ids.get(0), ids.get(1), ids.get(2));
    }

    /**
     * VERIFIED behaviour of the {@code attempts} column across a failed-then-retried batch, plus a
     * flagged observability gap. After a rollback the {@code recordAttempt()} increment is rolled back
     * with everything else, so a failed delivery is NOT durably counted: every row ends PUBLISHED with
     * {@code attempts == 1} even though delivery was actually attempted twice for the re-presented rows.
     *
     * <p>This is correct for crash-safety (the whole batch reverts atomically) but means {@code
     * attempts} cannot be used to detect a poison event that keeps failing — a monitoring blind spot
     * worth noting for Spec 07/08. The test asserts the real values rather than an idealised counter.
     */
    @Test
    void rolledBackBatchDoesNotDurablyCountFailedAttempts() {
        List<Long> ids = appendInOneTransaction("agg-attempts", "A0", "A1");
        publisher.failOnceOn(ids.get(1));

        assertThatThrownBy(relay::sweep).isInstanceOf(IllegalStateException.class);
        // Immediately after the failed batch: rolled back to PENDING with attempts reset to 0.
        List<Integer> afterFailure = attemptsFor("agg-attempts");
        assertThat(afterFailure).containsExactly(0, 0);
        assertThat(events.countByStatus(OutboxStatus.PENDING)).isEqualTo(2L);

        relay.sweep();

        // Both rows ended PUBLISHED (no loss, no stuck PENDING).
        assertThat(events.countByStatus(OutboxStatus.PENDING)).isZero();
        assertThat(events.countByStatus(OutboxStatus.PUBLISHED)).isEqualTo(2L);

        // FLAG: attempts == 1 for every row, including A0/A1 whose delivery was attempted twice. The
        // failed attempt left no durable trace because it was rolled back inside the batch transaction.
        assertThat(attemptsFor("agg-attempts")).containsExactly(1, 1);
    }

    private List<Integer> attemptsFor(String aggregateId) {
        return jdbc.queryForList(
                "SELECT attempts FROM outbox_event WHERE aggregate_id = ? ORDER BY id ASC", Integer.class, aggregateId);
    }

    /**
     * Interleaved aggregates: the global id order is a superset of each aggregate's order, so a single
     * batch covering two aggregates still delivers each aggregate's events in their own order.
     */
    @Test
    void perAggregateOrderingHoldsWhenTwoAggregatesAreInterleavedInOneBatch() {
        List<Long> all = new TransactionTemplate(transactionManager).execute(status -> {
            // Interleave A and B appends so their ids interleave globally.
            outbox.append(event("agg-A", "A0"));
            outbox.append(event("agg-B", "B0"));
            outbox.append(event("agg-A", "A1"));
            outbox.append(event("agg-B", "B1"));
            outbox.append(event("agg-A", "A2"));
            return jdbc.queryForList("SELECT id FROM outbox_event ORDER BY id ASC", Long.class);
        });

        relay.sweep();
        assertThat(events.countByStatus(OutboxStatus.PENDING)).isZero();

        // Global delivery order equals append (id) order...
        assertThat(publisher.deliveries()).containsExactlyElementsOf(all);

        // ...and each aggregate's own slice is in order (ids for A are positions 0,2,4; B are 1,3).
        List<Long> aIds = List.of(all.get(0), all.get(2), all.get(4));
        List<Long> bIds = List.of(all.get(1), all.get(3));
        assertThat(publisher.deliveries()).containsSubsequence(aIds);
        assertThat(publisher.deliveries()).containsSubsequence(bIds);
    }

    private OutboxEvent event(String aggregateId, String type) {
        return new OutboxEvent("auth.authorization", aggregateId, type, "{\"a\":\"" + aggregateId + "\"}", OCCURRED_AT);
    }

    private List<Long> appendInOneTransaction(String aggregateId, String... types) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            for (String type : types) {
                outbox.append(event(aggregateId, type));
            }
            return jdbc.queryForList(
                    "SELECT id FROM outbox_event WHERE aggregate_id = ? ORDER BY id ASC", Long.class, aggregateId);
        });
    }

    /**
     * The de-duplicated, first-seen order of ids in a delivery stream — the order an idempotent
     * consumer keyed by event id actually applies effects in. Redeliveries (later repeats of an id) are
     * dropped, so this is the stream that must be ascending by id for per-aggregate ordering to hold.
     */
    private static List<Long> firstOccurrenceOrder(List<Long> ids) {
        java.util.LinkedHashSet<Long> seen = new java.util.LinkedHashSet<>(ids);
        return List.copyOf(seen);
    }
}
