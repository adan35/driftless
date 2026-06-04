package io.driftless.idempotency;

import static org.assertj.core.api.Assertions.assertThatCode;

import io.driftless.outbox.internal.LoggingEventPublisher;
import io.driftless.outbox.internal.PublishedOutboxEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Unit test for the default in-process {@link LoggingEventPublisher}: publishing an event logs and
 * performs no external I/O (so the MVP seam is observable and side-effect-free).
 */
class LoggingEventPublisherTest {

    @Test
    void publishLogsWithoutThrowing() {
        LoggingEventPublisher publisher = new LoggingEventPublisher();
        PublishedOutboxEvent event = new PublishedOutboxEvent(
                7L,
                "auth.authorization",
                "auth-1",
                "AuthorizationCaptured",
                "{}",
                Instant.parse("2026-06-02T00:00:00Z"));

        assertThatCode(() -> publisher.publish(event)).doesNotThrowAnyException();
    }
}
