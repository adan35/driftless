package io.driftless.ledger.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One keyset page of an account's entries plus the cursor to fetch the next page.
 *
 * <p>{@code entries} are ordered oldest-first by the global, immutable {@code entry_seq}. {@code
 * nextCursor} is present exactly when the page was filled to the requested limit (so there may be
 * more) and empty once the visible stream is exhausted — a stable termination condition. An entry
 * that is assigned a lower {@code entry_seq} but commits after a higher-sequence row already returned
 * may be missed by a continuing page whose cursor advanced past it; it is never lost or duplicated,
 * and a fresh read from {@link EntryCursor#START} returns it once its writer commits.
 *
 * <p><strong>Additive contract.</strong> Returned by {@link Ledger#entriesAfter}; it adds to, and
 * does not alter, any existing frozen {@code io.driftless.ledger.api} shape.
 *
 * @param entries the page of entries, oldest first
 * @param nextCursor the cursor for the following page, or empty when the stream is exhausted
 */
public record EntryPage(List<JournalEntry> entries, Optional<EntryCursor> nextCursor) {

    public EntryPage {
        Objects.requireNonNull(nextCursor, "nextCursor");
        entries = List.copyOf(entries);
    }
}
