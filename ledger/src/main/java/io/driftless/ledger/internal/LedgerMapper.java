package io.driftless.ledger.internal;

import io.driftless.common.id.AccountId;
import io.driftless.common.id.EntryId;
import io.driftless.common.id.TxId;
import io.driftless.common.money.Money;
import io.driftless.ledger.api.Account;
import io.driftless.ledger.api.JournalEntry;
import io.driftless.ledger.api.Transaction;
import io.driftless.ledger.internal.persistence.AccountEntity;
import io.driftless.ledger.internal.persistence.JournalEntryEntity;
import io.driftless.ledger.internal.persistence.TransactionEntity;
import java.util.Currency;
import java.util.List;

/**
 * Translates between the internal JPA entities and the frozen {@code io.driftless.ledger.api}
 * records.
 *
 * <p>Centralizing the mapping here keeps the service thin and guarantees that JPA entities never
 * leak across the api boundary. All methods are pure and side-effect free.
 */
final class LedgerMapper {

    private LedgerMapper() {}

    static Account toApi(AccountEntity entity) {
        return new Account(
                AccountId.of(entity.getId()),
                entity.getType(),
                Currency.getInstance(entity.getCurrency()),
                entity.getName());
    }

    static JournalEntry toApi(JournalEntryEntity entity) {
        return new JournalEntry(
                EntryId.of(entity.getId()),
                TxId.of(entity.getTransaction().getId()),
                AccountId.of(entity.getAccountId()),
                entity.getDirection(),
                Money.of(entity.getAmountMinor(), Currency.getInstance(entity.getCurrency())),
                entity.getSequenceNo());
    }

    static List<JournalEntry> toApiEntries(List<JournalEntryEntity> entities) {
        return entities.stream().map(LedgerMapper::toApi).toList();
    }

    static Transaction toApi(TransactionEntity entity) {
        return new Transaction(
                TxId.of(entity.getId()),
                entity.getIdempotencyKey(),
                entity.getOccurredAt(),
                entity.getPostedAt(),
                entity.getDescription(),
                toApiEntries(entity.getEntries()));
    }
}
