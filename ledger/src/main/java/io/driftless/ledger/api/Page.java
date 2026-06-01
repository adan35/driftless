package io.driftless.ledger.api;

/**
 * A zero-based pagination request for streaming entries (reconciliation, statements).
 *
 * @param number zero-based page index
 * @param size maximum number of entries per page
 */
public record Page(int number, int size) {}
