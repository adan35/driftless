/**
 * Outbox SPI (Spec 02): read-only surfaces the idempotency/outbox module publishes for other modules
 * to integrate against without reaching into its internal persistence. {@link
 * io.driftless.outbox.spi.OutboxReconView} gives the Spec 07 reconciliation job the relay backlog
 * (stuck {@code PENDING} events) so its outbox-consistency check reads a published contract.
 */
package io.driftless.outbox.spi;
