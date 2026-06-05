package io.driftless.observability.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.driftless.outbox.spi.OutboxPublication;
import io.driftless.outbox.spi.OutboxPublicationListener;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Maps the relay's published domain-event stream onto Micrometer counters, via the published {@link
 * OutboxPublicationListener} seam — the spec's "instrument via outbox/domain events" path that keeps
 * the auth and token modules clean.
 *
 * <p><strong>Exactly-once counting.</strong> Relay delivery is at-least-once (a crash between publish
 * and mark-published re-presents an event), so every increment is guarded by a bounded dedup set
 * keyed on the persisted {@link OutboxPublication#id()}: a redelivered event is recognised and not
 * counted twice. The set is bounded (LRU) because the redelivery window is small — only events around
 * a crash can repeat.
 *
 * <p>The aggregate/event-type strings here are the outbox <em>wire protocol</em> (the same literals
 * the auth and token modules stamp on their outbox rows), not a code dependency on their internals.
 */
@Component
public class OutboxEventMetrics implements OutboxPublicationListener {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventMetrics.class);

    // Outbox wire protocol (aggregate/event types) — see auth/tokens lifecycle event constants.
    private static final String AGG_AUTH = "auth.authorization";
    private static final String EVT_AUTHORIZED = "AuthorizationAuthorized";
    private static final String EVT_DECLINED = "AuthorizationDeclined";
    private static final String EVT_CAPTURED = "AuthorizationCaptured";
    private static final String EVT_REVERSED = "AuthorizationReversed";
    private static final String AGG_TOKEN = "tokens.token";
    private static final String EVT_TOKEN_STATUS_CHANGED = "TokenStatusChanged";

    private static final int DEDUP_CAPACITY = 50_000;

    private final MeterRegistry registry;
    private final ObjectMapper objectMapper;

    private final Counter authAuthorized;
    private final Counter authDeclined;
    private final Counter authCaptured;
    private final Counter authReversed;

    /** Bounded LRU dedup of recently-counted event ids, for at-least-once redelivery. */
    private final Set<Long> counted = Collections.newSetFromMap(Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
            return size() > DEDUP_CAPACITY;
        }
    }));

    public OutboxEventMetrics(MeterRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        // Pre-register the auth lifecycle counters so they appear at 0 in /actuator/prometheus before
        // the first event — the dashboard panels and acceptance test see them immediately.
        this.authAuthorized = Counter.builder(MetricNames.AUTH_AUTHORIZATIONS)
                .description("Authorizations approved")
                .register(registry);
        this.authDeclined = Counter.builder(MetricNames.AUTH_DECLINES)
                .description("Authorizations declined")
                .register(registry);
        this.authCaptured = Counter.builder(MetricNames.AUTH_CAPTURES)
                .description("Captures settled")
                .register(registry);
        this.authReversed = Counter.builder(MetricNames.AUTH_COMPENSATING_REVERSALS)
                .description("Compensating (and explicit) reversals")
                .register(registry);
    }

    @Override
    public void onPublished(OutboxPublication publication) {
        if (!counted.add(publication.id())) {
            return; // already counted this event id; at-least-once redelivery
        }
        Counter.builder(MetricNames.OUTBOX_PUBLISHED)
                .tag(MetricNames.TAG_AGGREGATE_TYPE, publication.aggregateType())
                .tag(MetricNames.TAG_EVENT_TYPE, publication.eventType())
                .description("Events delivered by the outbox relay")
                .register(registry)
                .increment();

        if (AGG_AUTH.equals(publication.aggregateType())) {
            countAuth(publication.eventType());
        } else if (AGG_TOKEN.equals(publication.aggregateType())
                && EVT_TOKEN_STATUS_CHANGED.equals(publication.eventType())) {
            countTokenTransition(publication.payloadJson());
        }
    }

    private void countAuth(String eventType) {
        switch (eventType) {
            case EVT_AUTHORIZED -> authAuthorized.increment();
            case EVT_DECLINED -> authDeclined.increment();
            case EVT_CAPTURED -> authCaptured.increment();
            case EVT_REVERSED -> authReversed.increment();
            default -> {
                // an auth aggregate event we do not break out individually; the generic published
                // counter already recorded it.
            }
        }
    }

    private void countTokenTransition(String payloadJson) {
        String from = "CREATE";
        String to = "UNKNOWN";
        try {
            JsonNode node = objectMapper.readTree(payloadJson);
            if (node.hasNonNull("from")) {
                from = node.get("from").asText();
            }
            if (node.hasNonNull("to")) {
                to = node.get("to").asText();
            }
        } catch (Exception e) {
            log.debug("could not parse token status payload for metrics: {}", e.toString());
        }
        Counter.builder(MetricNames.TOKENS_STATUS_CHANGES)
                .tag(MetricNames.TAG_TRANSITION, from + "_to_" + to)
                .description("Token status changes by transition")
                .register(registry)
                .increment();
    }
}
