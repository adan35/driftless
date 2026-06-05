package io.driftless.recon.api;

/**
 * The kind of entity a {@link Offender} points at, so a dashboard / RCA report can group drift by
 * what is implicated.
 */
public enum OffenderType {

    /** A specific currency whose ledger-wide signed sum is non-zero. */
    CURRENCY,

    /** A posted transaction whose legs do not net to zero. */
    TRANSACTION,

    /** An account implicated in an unbalanced transaction or a per-account inconsistency. */
    ACCOUNT,

    /** An authorization implicated in a hold inconsistency (e.g. terminal but still holding). */
    AUTHORIZATION,

    /** An ACTIVE hold that is dangling (its authorization is terminal) or otherwise inconsistent. */
    HOLD,

    /** A stuck {@code PENDING} outbox event older than the threshold. */
    OUTBOX_EVENT
}
