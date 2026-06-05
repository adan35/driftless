package io.driftless.ledger.spi;

import java.util.UUID;

/**
 * A posted transaction whose legs do <em>not</em> net to zero in a currency — the precise offender a
 * reconciliation pass names. For a correct, balanced ledger this projection yields no rows.
 *
 * <p>Published on the ledger SPI (not the frozen {@code api}) so a consumer such as the Spec 07
 * reconciliation job can interrogate per-transaction balance without reaching into the ledger's
 * internal persistence.
 *
 * @param transactionId the offending transaction
 * @param currency the currency the imbalance is in
 * @param driftMinor signed net of the transaction's legs in that currency (non-zero by definition)
 */
public record UnbalancedTransaction(UUID transactionId, String currency, long driftMinor) {}
