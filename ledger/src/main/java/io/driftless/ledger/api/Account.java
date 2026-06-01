package io.driftless.ledger.api;

import io.driftless.common.id.AccountId;
import java.util.Currency;

/** Immutable account definition. */
public record Account(AccountId id, AccountType type, Currency currency, String name) {}
