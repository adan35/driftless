package io.driftless.idempotency;

import static io.driftless.idempotency.IdempotencyFixtures.account;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.driftless.common.id.AccountId;
import io.driftless.ledger.api.AccountType;
import io.driftless.ledger.api.Ledger;
import io.driftless.outbox.api.OutboxEvent;
import io.driftless.outbox.api.OutboxWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.UnexpectedRollbackException;

/**
 * Atomicity acceptance test: an event appended via {@link OutboxWriter} commits together with the
 * ledger write. If the surrounding transaction rolls back, neither the outbox row nor the ledger
 * rows survive — proving the outbox is transactional, not a side channel.
 */
@SpringBootTest
@Import(PostgresTestContainer.class)
class OutboxAtomicityIT {

    static final Instant POSTED_AT = Instant.parse("2026-06-02T00:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(POSTED_AT, ZoneOffset.UTC);
        }
    }

    @Autowired
    LedgerAndOutboxTxRunner runner;

    @Autowired
    Ledger ledger;

    @Autowired
    OutboxWriter outbox;

    @Autowired
    JdbcTemplate jdbc;

    private long outboxCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Long.class);
    }

    private long ledgerEntryCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM journal_entry", Long.class);
    }

    // --- rollback leaves no outbox row AND no ledger rows ---------------------------------------

    @Test
    void rollbackOfTheSurroundingTransactionLeavesNoOutboxRowAndNoLedgerRows() {
        AccountId asset = openUsd(AccountType.ASSET, "settlement");
        AccountId card = openUsd(AccountType.LIABILITY, "cardholder");
        long outboxBefore = outboxCount();
        long entriesBefore = ledgerEntryCount();

        assertThatThrownBy(() -> runner.postAndAppend("atomic-rollback", card, asset, 5_00L, true))
                .isInstanceOf(IllegalStateException.class);

        assertThat(outboxCount()).isEqualTo(outboxBefore);
        assertThat(ledgerEntryCount()).isEqualTo(entriesBefore);
    }

    // --- positive control: on commit, both the ledger rows and the outbox row are present --------

    @Test
    void commitOfTheSurroundingTransactionPersistsBothTheLedgerRowsAndTheOutboxRow() {
        AccountId asset = openUsd(AccountType.ASSET, "settlement");
        AccountId card = openUsd(AccountType.LIABILITY, "cardholder");
        long outboxBefore = outboxCount();
        long entriesBefore = ledgerEntryCount();

        runner.postAndAppend("atomic-commit", card, asset, 5_00L, false);

        assertThat(outboxCount()).isEqualTo(outboxBefore + 1);
        assertThat(ledgerEntryCount()).isEqualTo(entriesBefore + 2);
    }

    // --- append outside a transaction is a programming error (MANDATORY propagation) ------------

    @Test
    void appendOutsideATransactionIsRejected() {
        assertThatThrownBy(() -> outbox.append(
                        new OutboxEvent("ledger.transaction", "no-tx", "TransactionPosted", "{}", POSTED_AT)))
                .isInstanceOfAny(
                        org.springframework.transaction.IllegalTransactionStateException.class,
                        UnexpectedRollbackException.class);
    }

    private AccountId openUsd(AccountType type, String name) {
        AccountId id = AccountId.newId();
        ledger.openAccount(account(id, type, name));
        return id;
    }
}
