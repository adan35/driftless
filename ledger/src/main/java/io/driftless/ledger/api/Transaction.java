package io.driftless.ledger.api;

import io.driftless.common.id.TxId;
import java.time.Instant;
import java.util.List;

/**
 * An immutable, posted transaction (journal batch) and its balanced legs.
 *
 * <p>Mirrors the {@code transaction} row in Spec 01's data model plus its {@link JournalEntry}
 * children. {@code occurredAt} is business time; {@code postedAt} is when the ledger committed it.
 */
public record Transaction(
        TxId id,
        String idempotencyKey,
        Instant occurredAt,
        Instant postedAt,
        String description,
        List<JournalEntry> entries) {}
