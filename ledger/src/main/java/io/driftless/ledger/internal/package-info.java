/**
 * Spec 01 ledger implementation — <strong>internal, not part of the frozen contract</strong>.
 *
 * <p>Everything here (the {@code LedgerService}, JPA entities, repositories, mapper, persistence
 * wiring and the no-op {@code HoldView}) is an implementation detail behind {@code
 * io.driftless.ledger.api}/{@code spi}. Downstream modules must depend only on the frozen api/spi
 * packages and the {@code Ledger} bean — never on a type in this package. JPA entities never cross
 * the api boundary; the mapper translates them to api records.
 */
package io.driftless.ledger.internal;
