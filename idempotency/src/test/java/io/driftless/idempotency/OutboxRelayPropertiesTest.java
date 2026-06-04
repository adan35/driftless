package io.driftless.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import io.driftless.outbox.internal.OutboxRelayProperties;
import org.junit.jupiter.api.Test;

/** Pure unit tests for {@link OutboxRelayProperties} defaulting — no Spring context, no container. */
class OutboxRelayPropertiesTest {

    @Test
    void appliesSensibleDefaultsWhenValuesAreNull() {
        OutboxRelayProperties defaults = new OutboxRelayProperties(null, null, null);

        assertThat(defaults.pollDelayMillis()).isEqualTo(500L);
        assertThat(defaults.batchSize()).isEqualTo(100);
        assertThat(defaults.maxBatchesPerSweep()).isEqualTo(50);
    }

    @Test
    void keepsExplicitValuesWhenProvided() {
        OutboxRelayProperties explicit = new OutboxRelayProperties(250L, 10, 3);

        assertThat(explicit.pollDelayMillis()).isEqualTo(250L);
        assertThat(explicit.batchSize()).isEqualTo(10);
        assertThat(explicit.maxBatchesPerSweep()).isEqualTo(3);
    }
}
