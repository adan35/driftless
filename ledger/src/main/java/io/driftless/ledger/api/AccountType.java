package io.driftless.ledger.api;

/** The five classical double-entry account categories. */
public enum AccountType {
    ASSET, // e.g. settlement/funding asset
    LIABILITY, // e.g. cardholder funds the issuer owes
    EQUITY,
    REVENUE,
    EXPENSE
}
