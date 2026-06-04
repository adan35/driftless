package io.driftless.outbox.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the outbox relay.
 *
 * @param pollDelayMillis fixed delay between relay sweeps, in milliseconds
 * @param batchSize maximum number of pending events claimed per transaction
 * @param maxBatchesPerSweep cap on consecutive batches drained in one sweep, so a large backlog is
 *     worked down without a single sweep running unbounded
 */
@ConfigurationProperties(prefix = "driftless.outbox.relay")
public record OutboxRelayProperties(Long pollDelayMillis, Integer batchSize, Integer maxBatchesPerSweep) {

    public OutboxRelayProperties {
        pollDelayMillis = pollDelayMillis == null ? 500L : pollDelayMillis;
        batchSize = batchSize == null ? 100 : batchSize;
        maxBatchesPerSweep = maxBatchesPerSweep == null ? 50 : maxBatchesPerSweep;
    }
}
