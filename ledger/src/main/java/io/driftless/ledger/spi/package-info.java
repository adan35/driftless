/**
 * Ledger SPI.
 *
 * <p>Extension and read surfaces the ledger consults/publishes but whose business logic it does not
 * own. {@link io.driftless.ledger.spi.HoldView} (FROZEN as of the Spec 00/01 gate — see {@code
 * docs/contracts/ledger-interface.md} and ADR-0005) lets Spec 03 register active holds the ledger
 * subtracts when computing available balance. {@link io.driftless.ledger.spi.LedgerReconView} is a
 * <em>new</em>, read-only reconciliation view (Spec 07) over committed {@code journal_entry} state;
 * adding it does not alter the frozen {@code HoldView}/{@code api} shapes.
 */
package io.driftless.ledger.spi;
