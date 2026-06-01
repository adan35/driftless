/**
 * FROZEN public ledger contract.
 *
 * <p>This package is the cross-module API that every downstream feature codes against. It is frozen
 * as of the Spec 00/01 gate — see {@code docs/contracts/ledger-interface.md} and ADR-0005. Do not
 * change the shape of any type here (interfaces, records, enums, exceptions) without reopening that
 * gate, because parallel agents build against a stable contract, not a moving one.
 *
 * <p>Spec 00 publishes the contract shapes only; the implementation belongs to Spec 01.
 */
package io.driftless.ledger.api;
