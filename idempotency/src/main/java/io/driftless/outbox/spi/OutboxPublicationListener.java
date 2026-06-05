package io.driftless.outbox.spi;

/**
 * A published SPI letting a cross-cutting consumer observe the relay's domain-event stream without
 * binding to the outbox's internal {@code EventPublisher} seam (which the relay reserves for the
 * single delivery target — logging in the MVP, a broker adapter in production).
 *
 * <p>The relay invokes the registered listener for every event it delivers, in id order, inside the
 * publishing transaction. A default no-op listener is always present, so a host that wires its own
 * (Spec 08 maps the stream onto Micrometer counters) cleanly replaces it. Implementations must be
 * cheap and must dedup on {@link OutboxPublication#id()} — delivery is at-least-once.
 */
public interface OutboxPublicationListener {

    /**
     * Called once per delivered event, in id order, within the relay's publish transaction. Must not
     * throw for normal operation: a thrown exception aborts the batch and re-presents the events.
     *
     * @param publication the delivered event; dedup on {@link OutboxPublication#id()}
     */
    void onPublished(OutboxPublication publication);
}
