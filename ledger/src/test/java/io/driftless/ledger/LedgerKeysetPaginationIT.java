package io.driftless.ledger;

import static io.driftless.ledger.LedgerFixtures.USD;
import static io.driftless.ledger.LedgerFixtures.account;
import static io.driftless.ledger.LedgerFixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;

import io.driftless.common.id.AccountId;
import io.driftless.common.id.EntryId;
import io.driftless.ledger.api.Account;
import io.driftless.ledger.api.AccountType;
import io.driftless.ledger.api.EntryCursor;
import io.driftless.ledger.api.EntryPage;
import io.driftless.ledger.api.JournalEntry;
import io.driftless.ledger.api.Ledger;
import io.driftless.ledger.api.Page;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Keyset (seek) pagination and {@code findAccount} against real Postgres (Testcontainers), exercising
 * the additive {@link Ledger#entriesAfter} / {@link Ledger#findAccount} surface added in the
 * API-refinement pass.
 */
@SpringBootTest
@Import(PostgresTestContainer.class)
class LedgerKeysetPaginationIT {

    @Autowired
    Ledger ledger;

    @Autowired
    DataSource dataSource;

    private AccountId openUsd(String name) {
        AccountId id = AccountId.newId();
        ledger.openAccount(account(id, AccountType.LIABILITY, USD, name));
        return id;
    }

    /** Post a balanced 2-leg transfer; the {@code target} account gains exactly one entry. */
    private void postTo(AccountId target, AccountId source, String key, long minor) {
        ledger.post(transfer(key, source, target, minor, USD));
    }

    @Test
    void findAccountReturnsDetailsForKnownAndEmptyForUnknown() {
        AccountId id = AccountId.newId();
        ledger.openAccount(account(id, AccountType.ASSET, USD, "settlement-ish"));

        assertThat(ledger.findAccount(id)).hasValueSatisfying(a -> assertThat(a)
                .extracting(Account::id, Account::type, Account::currency, Account::name)
                .containsExactly(id, AccountType.ASSET, USD, "settlement-ish"));
        assertThat(ledger.findAccount(AccountId.newId())).isEmpty();
    }

    @Test
    void keysetPagingReturnsEveryEntryExactlyOnceEvenWithAConcurrentAppend() {
        AccountId target = openUsd("target");
        AccountId source = openUsd("source");
        for (int i = 0; i < 10; i++) {
            postTo(target, source, "seed-" + i, 100 + i);
        }

        int limit = 3;
        List<EntryId> seen = new ArrayList<>();

        // First page.
        EntryPage page = ledger.entriesAfter(target, EntryCursor.START, limit);
        page.entries().forEach(e -> seen.add(e.id()));
        assertThat(page.nextCursor()).isPresent();

        // A NEW entry is appended for this account between page 1 and the rest. A correct keyset
        // must still surface it exactly once (it sorts after the cursor) and never duplicate or skip.
        postTo(target, source, "appended-mid-iteration", 999);

        EntryCursor cursor = page.nextCursor().orElseThrow();
        while (true) {
            page = ledger.entriesAfter(target, cursor, limit);
            page.entries().forEach(e -> seen.add(e.id()));
            if (page.nextCursor().isEmpty()) {
                break;
            }
            cursor = page.nextCursor().orElseThrow();
        }

        // The full set for the account (offset read with a large page), as ground truth.
        List<EntryId> expected = ledger.entriesFor(target, new Page(0, 1000)).stream()
                .map(JournalEntry::id)
                .toList();

        assertThat(expected).hasSize(11); // 10 seeded + 1 appended mid-iteration
        assertThat(seen).doesNotHaveDuplicates();
        assertThat(seen).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void keysetPageCapsLimitAndTerminatesWithoutCursorAtStreamEnd() {
        AccountId target = openUsd("target-cap");
        AccountId source = openUsd("source-cap");
        for (int i = 0; i < 4; i++) {
            postTo(target, source, "cap-" + i, 50 + i);
        }

        // A limit larger than the data returns all four and no further cursor (stream exhausted).
        EntryPage page = ledger.entriesAfter(target, null, 100);
        assertThat(page.entries()).hasSize(4);
        assertThat(page.nextCursor()).isEmpty();

        // An over-cap limit is clamped server-side (<= 200) without error.
        EntryPage capped = ledger.entriesAfter(target, EntryCursor.START, 10_000);
        assertThat(capped.entries()).hasSize(4);
    }

    /**
     * Documents the REAL concurrent hazard the keyset wording must not overstate: a Postgres sequence
     * is non-transactional, so an entry can be ASSIGNED a lower {@code entry_seq} yet COMMIT after a
     * higher-sequence entry. A reader paging while the lower-seq row is still in flight transiently
     * omits it — but the durable ledger never loses or duplicates an entry, and a fresh read from
     * {@code START} returns the complete set once the in-flight writer commits.
     *
     * <p>The hazard is reproduced deterministically with two raw JDBC connections: connection A inserts
     * an entry (taking the lower {@code entry_seq}) and holds it uncommitted while connection B inserts
     * and commits a higher-{@code entry_seq} entry. The keyset read then sees B but not A.
     */
    @Test
    void inFlightLowerSequenceIsTransientlyOmittedButNeverLostFromAFreshRead() throws Exception {
        AccountId target = openUsd("ooo-target");
        AccountId source = openUsd("ooo-source");
        for (int i = 0; i < 5; i++) {
            postTo(target, source, "ooo-seed-" + i, 200 + i);
        }

        // A committed transaction the two out-of-order legs can hang off (FK-satisfying).
        UUID txId = UUID.randomUUID();
        insertTransaction(txId, "ooo-tx-" + txId);

        EntryId lowerSeqEntry = EntryId.newId(); // inserted first => lower entry_seq, held uncommitted
        EntryId higherSeqEntry = EntryId.newId(); // inserted second => higher entry_seq, committed

        Connection inFlight = dataSource.getConnection();
        try {
            inFlight.setAutoCommit(false);
            // Connection A: assign the LOWER entry_seq, but DO NOT commit yet (still in flight).
            insertEntry(inFlight, lowerSeqEntry.value(), txId, target, 11);

            // Connection B: assign the HIGHER entry_seq and COMMIT, so it is visible to readers.
            try (Connection committed = dataSource.getConnection()) {
                committed.setAutoCommit(true);
                insertEntry(committed, higherSeqEntry.value(), txId, target, 22);
            }

            // While A is in flight: a full keyset page is internally consistent (no duplicates,
            // strictly-increasing cursors) and surfaces the committed higher-seq entry. The
            // uncommitted lower-seq entry is transiently absent — exactly the documented behaviour.
            Paged duringFlight = pageAll(target, 3);
            assertThat(duringFlight.ids()).doesNotHaveDuplicates();
            assertThat(duringFlight.cursorSequences()).isSorted();
            assertThat(isStrictlyIncreasing(duringFlight.cursorSequences())).isTrue();
            assertThat(duringFlight.ids()).contains(higherSeqEntry);
            assertThat(duringFlight.ids()).doesNotContain(lowerSeqEntry);

            // The lower-seq writer commits.
            inFlight.commit();
        } finally {
            inFlight.close();
        }

        // A FRESH read from START now returns EVERY entry exactly once — no entry was lost.
        Paged afterCommit = pageAll(target, 3);
        assertThat(afterCommit.ids()).doesNotHaveDuplicates();
        assertThat(afterCommit.ids()).contains(lowerSeqEntry, higherSeqEntry);

        List<EntryId> groundTruth = ledger.entriesFor(target, new Page(0, 1000)).stream()
                .map(JournalEntry::id)
                .toList();
        assertThat(groundTruth).hasSize(7); // 5 seeded + 2 out-of-order
        assertThat(afterCommit.ids()).containsExactlyInAnyOrderElementsOf(groundTruth);
    }

    /** Page a target's whole stream via the keyset cursor, capturing entry ids and the cursor trail. */
    private Paged pageAll(AccountId target, int limit) {
        List<EntryId> ids = new ArrayList<>();
        List<Long> cursorSequences = new ArrayList<>();
        EntryCursor cursor = EntryCursor.START;
        while (true) {
            EntryPage page = ledger.entriesAfter(target, cursor, limit);
            page.entries().forEach(e -> ids.add(e.id()));
            if (page.nextCursor().isEmpty()) {
                break;
            }
            cursor = page.nextCursor().orElseThrow();
            cursorSequences.add(cursor.afterSequence());
        }
        return new Paged(ids, cursorSequences);
    }

    private static boolean isStrictlyIncreasing(List<Long> values) {
        for (int i = 1; i < values.size(); i++) {
            if (values.get(i) <= values.get(i - 1)) {
                return false;
            }
        }
        return true;
    }

    private void insertTransaction(UUID id, String idempotencyKey) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        """
                        INSERT INTO transaction (id, idempotency_key, occurred_at, posted_at, description)
                        VALUES (?, ?, ?, ?, ?)
                        """)) {
            OffsetDateTime now = OffsetDateTime.now();
            ps.setObject(1, id);
            ps.setString(2, idempotencyKey);
            ps.setObject(3, now);
            ps.setObject(4, now);
            ps.setString(5, "out-of-order visibility fixture");
            ps.executeUpdate();
        }
    }

    /** Insert one DEBIT leg directly; {@code entry_seq} is assigned by the DB DEFAULT nextval. */
    private void insertEntry(Connection connection, UUID id, UUID txId, AccountId account, int sequenceNo)
            throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                """
                INSERT INTO journal_entry
                    (id, transaction_id, account_id, direction, amount_minor, currency, sequence_no)
                VALUES (?, ?, ?, 'DEBIT', ?, 'USD', ?)
                """)) {
            ps.setObject(1, id);
            ps.setObject(2, txId);
            ps.setObject(3, account.value());
            ps.setLong(4, 4_242L);
            ps.setInt(5, sequenceNo);
            ps.executeUpdate();
        }
    }

    /** Captured result of a full keyset traversal: the entry ids seen and the cursor sequence trail. */
    private record Paged(List<EntryId> ids, List<Long> cursorSequences) {}
}
