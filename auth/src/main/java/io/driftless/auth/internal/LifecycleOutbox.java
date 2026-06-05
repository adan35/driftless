package io.driftless.auth.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.driftless.auth.internal.event.AuthorizationLifecycleEvent;
import io.driftless.auth.internal.persistence.AuthorizationEntity;
import io.driftless.outbox.api.OutboxEvent;
import io.driftless.outbox.api.OutboxWriter;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Appends an {@link AuthorizationLifecycleEvent} to the Spec 02 transactional outbox, in the caller's
 * current transaction (the outbox writer's {@code append} is {@code Propagation.MANDATORY}). The event
 * therefore commits or rolls back atomically with the state change that produced it.
 */
@RequiredArgsConstructor
@Component
class LifecycleOutbox {

    private final OutboxWriter outbox;
    private final ObjectMapper objectMapper;

    /** Append a lifecycle event of {@code eventType} describing {@code auth}'s current state. */
    void emit(AuthorizationEntity auth, String eventType, String reason, Instant occurredAt) {
        AuthorizationLifecycleEvent payload = new AuthorizationLifecycleEvent(
                auth.getId(),
                auth.getStatus(),
                auth.getPartnerRef(),
                reason,
                auth.getAmountMinor(),
                auth.getCurrency(),
                occurredAt);
        outbox.append(new OutboxEvent(
                AuthorizationLifecycleEvent.AGGREGATE_TYPE,
                auth.getId().toString(),
                eventType,
                json(payload),
                occurredAt));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize auth lifecycle payload", e);
        }
    }
}
