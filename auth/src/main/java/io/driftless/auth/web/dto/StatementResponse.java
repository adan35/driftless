package io.driftless.auth.web.dto;

import io.driftless.ledger.api.EntryPage;
import io.driftless.ledger.api.JournalEntry;
import java.util.List;
import java.util.UUID;

/**
 * Response for {@code GET /accounts/{id}/statement}: a keyset (seek) page of the account's entries
 * plus the opaque {@code nextCursor} to fetch the following page (absent at the end of the stream).
 *
 * @param account the account id
 * @param entries the page of entries, oldest first
 * @param nextCursor opaque cursor for the next page, or {@code null} when the stream is exhausted
 */
public record StatementResponse(UUID account, List<StatementEntry> entries, String nextCursor) {

    /** One entry on the statement. Money is integer minor units with an explicit currency. */
    public record StatementEntry(
            UUID id, UUID transactionId, String direction, long amountMinor, String currency, int sequenceNo) {

        static StatementEntry from(JournalEntry entry) {
            return new StatementEntry(
                    entry.id().value(),
                    entry.transactionId().value(),
                    entry.direction().name(),
                    entry.amount().amountMinor(),
                    entry.amount().currency().getCurrencyCode(),
                    entry.sequenceNo());
        }
    }

    public static StatementResponse from(UUID account, EntryPage page) {
        List<StatementEntry> entries =
                page.entries().stream().map(StatementEntry::from).toList();
        String nextCursor = page.nextCursor().map(c -> c.token()).orElse(null);
        return new StatementResponse(account, entries, nextCursor);
    }
}
