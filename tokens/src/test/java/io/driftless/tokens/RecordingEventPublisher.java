package io.driftless.tokens;

import io.driftless.outbox.internal.EventPublisher;
import io.driftless.outbox.internal.PublishedOutboxEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test double for the outbox relay's publisher seam that records every event handed to it, so a test
 * can assert that a transition propagated exactly one {@code TokenStatusChanged} event (and a replay
 * propagated none). Replaces the default {@code LoggingEventPublisher} via {@code @TestConfiguration}.
 */
class RecordingEventPublisher implements EventPublisher {

    private final List<PublishedOutboxEvent> published = new CopyOnWriteArrayList<>();

    @Override
    public void publish(PublishedOutboxEvent event) {
        published.add(event);
    }

    List<PublishedOutboxEvent> publishedOfType(String eventType) {
        return published.stream().filter(e -> e.eventType().equals(eventType)).toList();
    }

    void reset() {
        published.clear();
    }
}
