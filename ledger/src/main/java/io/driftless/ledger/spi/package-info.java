/**
 * FROZEN ledger SPI.
 *
 * <p>Extension points the ledger consults but does not own. {@link io.driftless.ledger.spi.HoldView}
 * lets Spec 03 register active holds that the ledger subtracts when computing available balance.
 * Frozen as of the Spec 00/01 gate — see {@code docs/contracts/ledger-interface.md} and ADR-0005.
 */
package io.driftless.ledger.spi;
