/**
 * Recon-owned JPA persistence: the {@code reconciliation_result} entity/repository plus read-only
 * repositories over the committed ledger / hold / outbox tables the balance-proof job reconciles.
 * Internal detail — never crosses the {@code io.driftless.recon.api} boundary.
 */
package io.driftless.recon.internal.persistence;
