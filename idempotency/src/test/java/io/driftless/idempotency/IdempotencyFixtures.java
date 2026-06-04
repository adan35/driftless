package io.driftless.idempotency;

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

/** Small builders shared by the idempotency integration tests (ledger posts for the atomicity case). */
final class IdempotencyFixtures {

    static final Currency USD = Currency.getInstance("USD");
    static final Instant OCCURRED_AT = Instant.parse("2026-06-01T10:15:30Z");

    private IdempotencyFixtures() {}

    static Account account(AccountId id, AccountType type, String name) {
        return new Account(id, type, USD, name);
    }

    static PostingLine debit(AccountId account, long minor) {
        return new PostingLine(account, Direction.DEBIT, Money.of(minor, USD));
    }

    static PostingLine credit(AccountId account, long minor) {
        return new PostingLine(account, Direction.CREDIT, Money.of(minor, USD));
    }

    /** A balanced two-line transfer of {@code minor} units from {@code from} (credit) to {@code to} (debit). */
    static PostingRequest transfer(String key, AccountId from, AccountId to, long minor) {
        return new PostingRequest(key, OCCURRED_AT, "transfer", List.of(debit(to, minor), credit(from, minor)));
    }
}
