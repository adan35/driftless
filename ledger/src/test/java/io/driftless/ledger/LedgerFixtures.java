package io.driftless.ledger;

import io.driftless.common.id.AccountId;
import io.driftless.common.money.Money;
import io.driftless.ledger.api.Account;
import io.driftless.ledger.api.AccountType;
import io.driftless.ledger.api.Direction;
import io.driftless.ledger.api.PostingLine;
import io.driftless.ledger.api.PostingRequest;
import java.time.Instant;
import java.util.Currency;
import java.util.List;

/** Small builders shared by the ledger integration tests. */
final class LedgerFixtures {

    static final Currency USD = Currency.getInstance("USD");
    static final Currency EUR = Currency.getInstance("EUR");
    static final Instant OCCURRED_AT = Instant.parse("2026-06-01T10:15:30Z");

    private LedgerFixtures() {}

    static Account account(AccountId id, AccountType type, Currency currency, String name) {
        return new Account(id, type, currency, name);
    }

    static PostingLine debit(AccountId account, long minor, Currency currency) {
        return new PostingLine(account, Direction.DEBIT, Money.of(minor, currency));
    }

    static PostingLine credit(AccountId account, long minor, Currency currency) {
        return new PostingLine(account, Direction.CREDIT, Money.of(minor, currency));
    }

    /** A balanced two-line transfer of {@code minor} units from {@code from} (credit) to {@code to} (debit). */
    static PostingRequest transfer(String key, AccountId from, AccountId to, long minor, Currency currency) {
        return new PostingRequest(
                key, OCCURRED_AT, "transfer", List.of(debit(to, minor, currency), credit(from, minor, currency)));
    }

    static PostingRequest request(String key, List<PostingLine> lines) {
        return new PostingRequest(key, OCCURRED_AT, "test", lines);
    }
}
