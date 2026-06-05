package io.driftless.recon.api;

/**
 * One implicated entity surfaced by a failed reconciliation check, carrying enough to do a
 * root-cause analysis without re-querying the ledger.
 *
 * @param type what kind of entity {@code reference} names
 * @param reference the entity's identifier (a transaction id, account id, currency code, or outbox
 *     event id) in canonical string form
 * @param currency the ISO-4217 currency the drift was observed in, when applicable
 * @param driftMinor signed drift attributed to this entity, in minor units ({@code 0} when not a
 *     monetary discrepancy, e.g. a stuck outbox event)
 * @param detail a short, log-safe human description of why this entity is implicated
 */
public record Offender(OffenderType type, String reference, String currency, long driftMinor, String detail) {}
