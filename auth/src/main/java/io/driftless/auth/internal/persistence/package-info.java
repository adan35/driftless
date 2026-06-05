/**
 * JPA persistence for the Spec 03 saga: the {@code authorization} aggregate and the {@code hold} rows
 * that feed the ledger {@code HoldView}. Thin, write-once-where-possible adapters scoped to this
 * module; no business decisions live here.
 */
package io.driftless.auth.internal.persistence;
