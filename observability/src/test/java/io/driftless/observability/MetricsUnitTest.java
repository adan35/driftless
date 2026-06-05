package io.driftless.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.driftless.ledger.api.BalanceInvariantViolation;
import io.driftless.ledger.api.Ledger;
import io.driftless.ledger.api.PostingRequest;
import io.driftless.ledger.api.PostingResult;
import io.driftless.observability.metrics.MeteredLedger;
import io.driftless.observability.metrics.MetricNames;
import io.driftless.observability.metrics.OutboxEventMetrics;
import io.driftless.observability.metrics.ReconMetrics;
import io.driftless.observability.metrics.TimedRuleEngine;
import io.driftless.outbox.spi.OutboxPublication;
import io.driftless.recon.api.CheckResult;
import io.driftless.recon.api.ReconCheck;
import io.driftless.recon.api.ReconciliationResult;
import io.driftless.rules.api.AuthContext;
import io.driftless.rules.api.RuleEngine;
import io.driftless.rules.api.RuleResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Fast, Docker-free unit tests over the Micrometer instrumentation: each component is exercised
 * against a {@link SimpleMeterRegistry} to prove the meter names, tags and values the dashboards and
 * the {@code /actuator/prometheus} integration test depend on.
 */
class MetricsUnitTest {

    @Test
    void reconGaugesSeedZeroAndReflectRuns() {
        MeterRegistry registry = new SimpleMeterRegistry();
        ReconMetrics metrics = new ReconMetrics(registry);

        // Seeded green/zero before any run.
        assertThat(registry.get(MetricNames.RECON_DRIFT_AMOUNT).gauge().value()).isZero();
        assertThat(registry.get(MetricNames.RECON_RUN_PASSED).gauge().value()).isEqualTo(1.0);

        metrics.onReconciliation(passing());
        assertThat(registry.get(MetricNames.RECON_DRIFT_AMOUNT).gauge().value()).isZero();
        assertThat(registry.get(MetricNames.RECON_RUN_PASSED).gauge().value()).isEqualTo(1.0);
        assertThat(registry.get(MetricNames.RECON_RUNS)
                        .tag(MetricNames.TAG_OUTCOME, "pass")
                        .counter()
                        .count())
                .isEqualTo(1.0);

        metrics.onReconciliation(failing(4200L));
        assertThat(registry.get(MetricNames.RECON_DRIFT_AMOUNT).gauge().value()).isEqualTo(4200.0);
        assertThat(registry.get(MetricNames.RECON_RUN_PASSED).gauge().value()).isZero();
        assertThat(registry.get(MetricNames.RECON_STUCK_OUTBOX).gauge().value()).isEqualTo(3.0);
        assertThat(registry.get(MetricNames.RECON_RUNS)
                        .tag(MetricNames.TAG_OUTCOME, "fail")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void outboxEventMetricsCountOnceAndBreakOutAuthAndTokens() {
        MeterRegistry registry = new SimpleMeterRegistry();
        OutboxEventMetrics metrics = new OutboxEventMetrics(registry, new ObjectMapper());

        // Pre-registered at zero so the panels and the acceptance test see it immediately.
        assertThat(registry.get(MetricNames.AUTH_COMPENSATING_REVERSALS)
                        .counter()
                        .count())
                .isZero();

        OutboxPublication reversed =
                new OutboxPublication(1L, "auth.authorization", "a1", "AuthorizationReversed", "{}", Instant.EPOCH);
        metrics.onPublished(reversed);
        metrics.onPublished(reversed); // at-least-once redelivery — must NOT double count
        assertThat(registry.get(MetricNames.AUTH_COMPENSATING_REVERSALS)
                        .counter()
                        .count())
                .isEqualTo(1.0);

        metrics.onPublished(
                new OutboxPublication(2L, "auth.authorization", "a2", "AuthorizationAuthorized", "{}", Instant.EPOCH));
        assertThat(registry.get(MetricNames.AUTH_AUTHORIZATIONS).counter().count())
                .isEqualTo(1.0);

        metrics.onPublished(new OutboxPublication(
                3L,
                "tokens.token",
                "t1",
                "TokenStatusChanged",
                "{\"from\":\"ACTIVE\",\"to\":\"SUSPENDED\"}",
                Instant.EPOCH));
        assertThat(registry.get(MetricNames.TOKENS_STATUS_CHANGES)
                        .tag(MetricNames.TAG_TRANSITION, "ACTIVE_to_SUSPENDED")
                        .counter()
                        .count())
                .isEqualTo(1.0);

        assertThat(registry.get(MetricNames.OUTBOX_PUBLISHED)
                        .tag(MetricNames.TAG_AGGREGATE_TYPE, "auth.authorization")
                        .tag(MetricNames.TAG_EVENT_TYPE, "AuthorizationReversed")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void timedRuleEngineRecordsLatencyHistogramAndDecisions() {
        MeterRegistry registry = new SimpleMeterRegistry();
        RuleEngine delegate = context -> RuleResult.decline("RULE-1", "LIMIT_EXCEEDED");
        TimedRuleEngine timed = new TimedRuleEngine(delegate, registry);

        RuleResult result = timed.evaluate((AuthContext) null);

        assertThat(result.reasonCode()).isEqualTo("LIMIT_EXCEEDED");
        assertThat(registry.get(MetricNames.RULES_EVALUATION).timer().count()).isEqualTo(1L);
        assertThat(registry.get(MetricNames.RULES_DECISIONS)
                        .tag(MetricNames.TAG_DECISION, "DECLINE")
                        .tag(MetricNames.TAG_REASON, "LIMIT_EXCEEDED")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void meteredLedgerCountsAcceptedAndRejectedPosts() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MeteredLedger accepting = new MeteredLedger(new StubLedger(false), registry);

        accepting.post((PostingRequest) null);
        assertThat(registry.get(MetricNames.LEDGER_POSTINGS).counter().count()).isEqualTo(1.0);
        assertThat(registry.get(MetricNames.LEDGER_REJECTED).counter().count()).isZero();

        MeterRegistry rejectRegistry = new SimpleMeterRegistry();
        MeteredLedger rejecting = new MeteredLedger(new StubLedger(true), rejectRegistry);
        assertThatThrownBy(() -> rejecting.post((PostingRequest) null)).isInstanceOf(BalanceInvariantViolation.class);
        assertThat(rejectRegistry.get(MetricNames.LEDGER_REJECTED).counter().count())
                .isEqualTo(1.0);
        assertThat(rejectRegistry.get(MetricNames.LEDGER_POSTINGS).counter().count())
                .isZero();
    }

    @Test
    void meteredLedgerDelegatesReadMethodsFaithfullyAndDoesNotMeterThem() {
        MeterRegistry registry = new SimpleMeterRegistry();
        RecordingLedger delegate = new RecordingLedger();
        MeteredLedger metered = new MeteredLedger(delegate, registry);

        io.driftless.common.id.AccountId account = io.driftless.common.id.AccountId.newId();
        io.driftless.common.id.TxId tx = io.driftless.common.id.TxId.newId();
        io.driftless.ledger.api.Page page = new io.driftless.ledger.api.Page(0, 10);

        // Reads must pass straight through: same arguments in, the delegate's exact return out.
        assertThat(metered.balanceOf(account)).isSameAs(delegate.balanceReturn);
        assertThat(delegate.lastBalanceArg).isSameAs(account);
        assertThat(metered.findTransaction(tx)).isSameAs(delegate.txReturn);
        assertThat(delegate.lastTxArg).isSameAs(tx);
        assertThat(metered.entriesFor(account, page)).isSameAs(delegate.entriesReturn);
        assertThat(delegate.lastEntriesAccountArg).isSameAs(account);
        assertThat(delegate.lastEntriesPageArg).isSameAs(page);

        // None of the read paths touch the write meters — post() is the only thing that moves them.
        assertThat(registry.get(MetricNames.LEDGER_POSTINGS).counter().count()).isZero();
        assertThat(registry.get(MetricNames.LEDGER_REJECTED).counter().count()).isZero();
    }

    @Test
    void reconOutboxCheckWithNullDetailDoesNotThrowAndCountsStuck() {
        MeterRegistry registry = new SimpleMeterRegistry();
        ReconMetrics metrics = new ReconMetrics(registry);

        // I4/W2: a future fail(...) could carry a null detail. The listener must never NPE (which, on a
        // synchronous in-transaction listener, would have rolled back the persisted recon result).
        ReconciliationResult nullDetail = new ReconciliationResult(
                UUID.randomUUID(),
                Instant.now(),
                false,
                0L,
                List.of(CheckResult.fail(ReconCheck.OUTBOX_CONSISTENCY, 0L, null)),
                List.of());

        metrics.onReconciliation(nullDetail);

        assertThat(registry.get(MetricNames.RECON_RUN_PASSED).gauge().value()).isZero();
        // Falls back to 1 when the stuck count can not be parsed from a null/odd detail.
        assertThat(registry.get(MetricNames.RECON_STUCK_OUTBOX).gauge().value()).isEqualTo(1.0);
    }

    @Test
    void timedRuleEngineReusesOneCachedCounterPerDecisionAndReason() {
        MeterRegistry registry = new SimpleMeterRegistry();
        RuleEngine delegate = context -> RuleResult.decline("RULE-1", "LIMIT_EXCEEDED");
        TimedRuleEngine timed = new TimedRuleEngine(delegate, registry);

        // W3: steady-state evaluate() must not register a new meter per call — the same (decision,reason)
        // counter is resolved once and only incremented thereafter.
        timed.evaluate((AuthContext) null);
        timed.evaluate((AuthContext) null);
        timed.evaluate((AuthContext) null);

        assertThat(registry.get(MetricNames.RULES_DECISIONS)
                        .tag(MetricNames.TAG_DECISION, "DECLINE")
                        .tag(MetricNames.TAG_REASON, "LIMIT_EXCEEDED")
                        .counter()
                        .count())
                .isEqualTo(3.0);
        // Exactly one decisions counter meter exists for this single (decision,reason) pair.
        assertThat(registry.find(MetricNames.RULES_DECISIONS).counters()).hasSize(1);
    }

    private static ReconciliationResult passing() {
        return new ReconciliationResult(
                UUID.randomUUID(),
                Instant.now(),
                true,
                0L,
                List.of(
                        CheckResult.pass(ReconCheck.GLOBAL_ZERO, "ok"),
                        CheckResult.pass(ReconCheck.PER_TRANSACTION, "ok"),
                        CheckResult.pass(ReconCheck.HOLD_CONSISTENCY, "ok"),
                        CheckResult.pass(ReconCheck.OUTBOX_CONSISTENCY, "no stuck PENDING outbox events")),
                List.of());
    }

    private static ReconciliationResult failing(long drift) {
        return new ReconciliationResult(
                UUID.randomUUID(),
                Instant.now(),
                false,
                drift,
                List.of(
                        CheckResult.fail(ReconCheck.PER_TRANSACTION, drift, "unbalanced"),
                        CheckResult.fail(ReconCheck.OUTBOX_CONSISTENCY, 0L, "3 stuck PENDING outbox event(s)")),
                List.of());
    }

    /** Minimal {@link Ledger} stub for the decorator test: only {@code post} is exercised. */
    private static final class StubLedger implements Ledger {
        private final boolean reject;

        StubLedger(boolean reject) {
            this.reject = reject;
        }

        @Override
        public PostingResult post(PostingRequest request) {
            if (reject) {
                throw new BalanceInvariantViolation("unbalanced");
            }
            return null;
        }

        @Override
        public io.driftless.ledger.api.Account openAccount(io.driftless.ledger.api.Account account) {
            return account;
        }

        @Override
        public io.driftless.ledger.api.Balance balanceOf(io.driftless.common.id.AccountId account) {
            return null;
        }

        @Override
        public io.driftless.ledger.api.Transaction findTransaction(io.driftless.common.id.TxId id) {
            return null;
        }

        @Override
        public List<io.driftless.ledger.api.JournalEntry> entriesFor(
                io.driftless.common.id.AccountId account, io.driftless.ledger.api.Page page) {
            return List.of();
        }
    }

    /** Records arguments and returns sentinels, to prove {@link MeteredLedger} delegates reads verbatim. */
    private static final class RecordingLedger implements Ledger {
        final io.driftless.ledger.api.Balance balanceReturn = new io.driftless.ledger.api.Balance(
                io.driftless.common.id.AccountId.newId(),
                java.util.Currency.getInstance("USD"),
                io.driftless.common.money.Money.of(0L, "USD"),
                io.driftless.common.money.Money.of(0L, "USD"));
        final io.driftless.ledger.api.Transaction txReturn = new io.driftless.ledger.api.Transaction(
                io.driftless.common.id.TxId.newId(), "k", Instant.EPOCH, Instant.EPOCH, "d", List.of());
        final List<io.driftless.ledger.api.JournalEntry> entriesReturn = List.of();

        io.driftless.common.id.AccountId lastBalanceArg;
        io.driftless.common.id.TxId lastTxArg;
        io.driftless.common.id.AccountId lastEntriesAccountArg;
        io.driftless.ledger.api.Page lastEntriesPageArg;

        @Override
        public PostingResult post(PostingRequest request) {
            return null;
        }

        @Override
        public io.driftless.ledger.api.Account openAccount(io.driftless.ledger.api.Account account) {
            return account;
        }

        @Override
        public io.driftless.ledger.api.Balance balanceOf(io.driftless.common.id.AccountId account) {
            this.lastBalanceArg = account;
            return balanceReturn;
        }

        @Override
        public io.driftless.ledger.api.Transaction findTransaction(io.driftless.common.id.TxId id) {
            this.lastTxArg = id;
            return txReturn;
        }

        @Override
        public List<io.driftless.ledger.api.JournalEntry> entriesFor(
                io.driftless.common.id.AccountId account, io.driftless.ledger.api.Page page) {
            this.lastEntriesAccountArg = account;
            this.lastEntriesPageArg = page;
            return entriesReturn;
        }
    }
}
