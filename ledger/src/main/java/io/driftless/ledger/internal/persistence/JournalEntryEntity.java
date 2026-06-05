package io.driftless.ledger.internal.persistence;

import io.driftless.ledger.api.Direction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * JPA row for the {@code journal_entry} table — one immutable, append-only leg of a transaction.
 *
 * <p>Append-only by construction: there is no setter and no code path issues {@code UPDATE}/{@code
 * DELETE}; the database enforces the same with a trigger (migration {@code V2}). {@code amountMinor}
 * is strictly positive minor units; {@link Direction} carries the sign. The owning transaction is a
 * lazy association so summation queries never trigger an N+1 load.
 */
@Entity
@Table(name = "journal_entry")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JournalEntryEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false, updatable = false)
    private TransactionEntity transaction;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, updatable = false, length = 6)
    private Direction direction;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "sequence_no", nullable = false, updatable = false)
    private int sequenceNo;

    /**
     * Global, strictly-monotonic insertion sequence assigned by the database ({@code entry_seq}
     * DEFAULT {@code nextval}, migration {@code V3}). It is the stable sort key for keyset (seek)
     * pagination: immutable, unique, and always greater for a later-appended row. Database-generated,
     * so it is read-only to the mapping ({@code insertable=false}) — never set in application code.
     */
    @Column(name = "entry_seq", insertable = false, updatable = false)
    private long entrySeq;

    public JournalEntryEntity(
            UUID id,
            TransactionEntity transaction,
            UUID accountId,
            Direction direction,
            long amountMinor,
            String currency,
            int sequenceNo) {
        this.id = id;
        this.transaction = transaction;
        this.accountId = accountId;
        this.direction = direction;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.sequenceNo = sequenceNo;
    }
}
