package io.driftless.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import io.driftless.outbox.api.OutboxEvent;
import io.driftless.outbox.api.OutboxWriter;
import io.driftless.outbox.internal.OutboxRelay;
import io.driftless.outbox.internal.persistence.OutboxEventRepository;
import io.driftless.outbox.internal.persistence.OutboxStatus;
import io.driftless.outbox.spi.OutboxPublicationListener;
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
 * W1 hardening (Review 08): the relay's published-event observation seam must never be able to break
 * delivery. {@code OutboxPublicationListener.onPublished} runs inside the relay's publish transaction;
 * a throwing listener (registry/OOM/NPE in a misbehaving or future metrics consumer) previously rolled
 * the whole batch back, leaving money-adjacent events stuck {@code PENDING} in a redelivery loop.
 *
 * <p>These tests pin the fix: a listener that throws on <em>every</em> event is logged and swallowed
 * per event, so the batch still commits — each event lands {@code PUBLISHED}, is delivered to the real
 * {@link io.driftless.outbox.internal.EventPublisher}, and ordering is preserved. Observation cannot
 * wedge the relay.
 */
@SpringBootTest(properties = "driftless.outbox.relay.batch-size=10")
@Import(PostgresTestContainer.class)
class OutboxPublicationListenerFailureIT {

    static final Instant OCCURRED_AT = Instant.parse("2026-06-02T00:00:00Z");

    /** A publication listener that always throws — the worst-case misbehaving observer. */
    static final class ThrowingPublicationListener implements OutboxPublicationListener {
        private final List<Long> observed = new CopyOnWriteArrayList<>();

        @Override
        public void onPublished(io.driftless.outbox.spi.OutboxPublication publication) {
            observed.add(publication.id());
            throw new IllegalStateException("simulated observation failure for event " + publication.id());
        }

        List<Long> observed() {
            return List.copyOf(observed);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        Clock clock() {
            return Clock.fixed(OCCURRED_AT, ZoneOffset.UTC);
        }

        @Bean
        RecordingEventPublisher recordingEventPublisher() {
            return new RecordingEventPublisher();
        }

        @Bean
        ThrowingPublicationListener throwingPublicationListener() {
            return new ThrowingPublicationListener();
        }
    }

    @Autowired
    OutboxWriter outbox;

    @Autowired
    OutboxRelay relay;

    @Autowired
    OutboxEventRepository events;

    @Autowired
    RecordingEventPublisher publisher;

    @Autowired
    ThrowingPublicationListener listener;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE TABLE outbox_event RESTART IDENTITY");
        publisher.reset();
    }

    @Test
    void aThrowingPublicationListenerDoesNotPreventEventsBeingMarkedPublishedOrDelivered() {
        List<Long> ids = appendInOneTransaction("agg-obs", "E0", "E1", "E2");

        // The sweep completes normally despite the listener throwing on every event.
        relay.sweep();

        // Delivery integrity: nothing stuck PENDING, every row marked PUBLISHED.
        assertThat(events.countByStatus(OutboxStatus.PENDING)).isZero();
        assertThat(events.countByStatus(OutboxStatus.PUBLISHED)).isEqualTo(3L);

        // The real delivery target still received every event, in id order.
        assertThat(publisher.consumed()).containsExactlyElementsOf(ids);

        // The listener was actually invoked (and threw) for each event — proving the guard, not a skip.
        assertThat(listener.observed()).containsExactlyElementsOf(ids);
    }

    private List<Long> appendInOneTransaction(String aggregateId, String... types) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            for (String type : types) {
                outbox.append(new OutboxEvent(
                        "auth.authorization", aggregateId, type, "{\"a\":\"" + aggregateId + "\"}", OCCURRED_AT));
            }
            return jdbc.queryForList(
                    "SELECT id FROM outbox_event WHERE aggregate_id = ? ORDER BY id ASC", Long.class, aggregateId);
        });
    }
}
