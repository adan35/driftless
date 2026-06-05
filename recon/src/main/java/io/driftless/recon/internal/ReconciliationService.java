package io.driftless.recon.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.driftless.auth.spi.DanglingHold;
import io.driftless.auth.spi.HoldBalanceMismatch;
import io.driftless.auth.spi.HoldReconView;
import io.driftless.ledger.spi.LedgerReconView;
import io.driftless.ledger.spi.UnbalancedTransaction;
import io.driftless.outbox.spi.OutboxReconView;
import io.driftless.outbox.spi.StuckOutboxEvent;
import io.driftless.recon.api.CheckResult;
import io.driftless.recon.api.Offender;
import io.driftless.recon.api.OffenderType;
import io.driftless.recon.api.ReconCheck;
import io.driftless.recon.api.ReconciliationResult;
import io.driftless.recon.internal.config.ReconProperties;
import io.driftless.recon.internal.persistence.ReconciliationResultEntity;
import io.driftless.recon.internal.persistence.ReconciliationResultRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Spec 07 continuous balance-proof job: it <em>proves</em> zero drift by summation over committed
 * state and persists a structured {@link ReconciliationResult} as evidence.
 *
 * <p>It runs four falsifiable checks — {@link ReconCheck#GLOBAL_ZERO}, {@link
 * ReconCheck#PER_TRANSACTION}, {@link ReconCheck#HOLD_CONSISTENCY}, {@link
 * ReconCheck#OUTBOX_CONSISTENCY} — reading the ledger, holds and outbox through the owning modules'
 * <strong>published read SPIs</strong> ({@link LedgerReconView}, {@link HoldReconView}, {@link
 * OutboxReconView}); it never binds to a sibling module's internal persistence. On any failure it
 * surfaces the offending transactions/accounts/holds for root-cause analysis. It
 * <strong>never</strong> edits the ledger to "fix" drift: corrections are deliberate, human-decided
 * compensating posts (out of scope here).
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final LedgerReconView ledgerRecon;
    private final HoldReconView holdRecon;
    private final OutboxReconView outboxRecon;
    private final ReconciliationResultRepository results;
    private final ObjectMapper objectMapper;
    private final ReconProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public ReconciliationService(
            LedgerReconView ledgerRecon,
            HoldReconView holdRecon,
            OutboxReconView outboxRecon,
            ReconciliationResultRepository results,
            ObjectMapper objectMapper,
            ReconProperties properties,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        this.ledgerRecon = ledgerRecon;
        this.holdRecon = holdRecon;
        this.outboxRecon = outboxRecon;
        this.results = results;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * Run every check over the current committed state, persist the structured result, and return it.
     * Read-mostly: the only write is the append of the {@code reconciliation_result} row.
     */
    @Transactional
    public ReconciliationResult run() {
        Instant ranAt = clock.instant();
        List<String> currencies = ledgerRecon.currencies();
        List<UnbalancedTransaction> unbalanced = ledgerRecon.findUnbalancedTransactions();

        List<Offender> offenders = new ArrayList<>();
        List<CheckResult> checks = new ArrayList<>();
        checks.add(checkGlobalZero(currencies, unbalanced, offenders));
        checks.add(checkPerTransaction(unbalanced, offenders));
        checks.add(checkHoldConsistency(offenders));
        checks.add(checkOutboxConsistency(ranAt, offenders));

        boolean passed = checks.stream().allMatch(CheckResult::passed);
        // Headline monetary drift, without double-counting: GLOBAL_ZERO's |net sum| is definitionally
        // subsumed by PER_TRANSACTION's sum of |per-transaction nets| (|Σ netᵢ| ≤ Σ|netᵢ|), so it is a
        // pass/fail cross-check that keeps its own reported figure but is not added to the total again.
        long totalDriftMinor = checks.stream()
                .filter(c -> c.check() != ReconCheck.GLOBAL_ZERO)
                .mapToLong(c -> Math.abs(c.driftMinor()))
                .sum();

        ReconciliationResult result =
                new ReconciliationResult(UUID.randomUUID(), ranAt, passed, totalDriftMinor, checks, offenders);
        persist(result);

        // Publish the structured result as a Spring application event so cross-cutting consumers (the
        // Spec 08 observability module) can update the zero-drift gauges off the recon path without
        // binding to this service. No listener is required; recon-only contexts simply ignore it.
        eventPublisher.publishEvent(result);

        if (passed) {
            log.info("reconciliation {} PASSED currencies={} drift=0", result.id(), currencies.size());
        } else {
            log.warn(
                    "reconciliation {} FAILED totalDrift={} offenders={} failedChecks={}",
                    result.id(),
                    totalDriftMinor,
                    offenders.size(),
                    checks.stream()
                            .filter(c -> !c.passed())
                            .map(CheckResult::check)
                            .toList());
        }
        return result;
    }

    /** The most recent persisted run, for the dashboard's "latest" read. */
    @Transactional(readOnly = true)
    public Optional<ReconciliationResult> latest() {
        return results.findFirstByOrderByRanAtDescIdDesc().map(this::toResult);
    }

    /** A persisted run by id (for the RCA endpoint). */
    @Transactional(readOnly = true)
    public Optional<ReconciliationResult> find(UUID id) {
        return results.findById(id).map(this::toResult);
    }

    // --- checks ----------------------------------------------------------------------------------

    /** Global zero: the signed sum of every journal entry is 0 per currency, ledger-wide. */
    private CheckResult checkGlobalZero(
            List<String> currencies, List<UnbalancedTransaction> unbalanced, List<Offender> offenders) {
        long drift = 0L;
        List<Offender> local = new ArrayList<>();
        for (String currency : currencies) {
            long sum = ledgerRecon.globalSignedSumMinor(currency);
            if (sum != 0L) {
                drift += Math.abs(sum);
                local.add(new Offender(
                        OffenderType.CURRENCY,
                        currency,
                        currency,
                        sum,
                        "ledger-wide signed sum is non-zero (%d minor units)".formatted(sum)));
            }
        }
        if (drift == 0L) {
            return CheckResult.pass(
                    ReconCheck.GLOBAL_ZERO, "signed sum 0 across %d currencies".formatted(currencies.size()));
        }

        // Name the accounts touched by the unbalanced transactions, so an RCA can see which accounts a
        // ledger-wide imbalance implicates (the transactions themselves are named by PER_TRANSACTION).
        List<UUID> txIds =
                unbalanced.stream().map(UnbalancedTransaction::transactionId).toList();
        if (!txIds.isEmpty()) {
            for (UUID accountId : capped(ledgerRecon.findAccountsForTransactions(txIds))) {
                local.add(new Offender(
                        OffenderType.ACCOUNT,
                        accountId.toString(),
                        null,
                        0L,
                        "account is a leg of an unbalanced transaction"));
            }
        }
        offenders.addAll(local);
        return CheckResult.fail(
                ReconCheck.GLOBAL_ZERO, drift, "%d unbalanced transaction(s)".formatted(unbalanced.size()));
    }

    /**
     * Per-transaction balance: every single posted transaction's signed legs net to zero per
     * currency. Falsifiable and strictly stronger than {@code GLOBAL_ZERO}: it names each offending
     * transaction even when two unbalanced transactions coincidentally offset in the global sum.
     */
    private CheckResult checkPerTransaction(List<UnbalancedTransaction> unbalanced, List<Offender> offenders) {
        if (unbalanced.isEmpty()) {
            return CheckResult.pass(ReconCheck.PER_TRANSACTION, "every transaction's legs net to zero");
        }
        // Drift is summed over EVERY unbalanced transaction; only the named offenders are capped.
        long drift =
                unbalanced.stream().mapToLong(tx -> Math.abs(tx.driftMinor())).sum();
        for (UnbalancedTransaction tx : capped(unbalanced)) {
            offenders.add(new Offender(
                    OffenderType.TRANSACTION,
                    tx.transactionId().toString(),
                    tx.currency(),
                    tx.driftMinor(),
                    "transaction legs net %d minor units (not zero)".formatted(tx.driftMinor())));
        }
        return CheckResult.fail(
                ReconCheck.PER_TRANSACTION, drift, "%d unbalanced transaction(s)".formatted(unbalanced.size()));
    }

    /**
     * Hold consistency from an <em>independent</em> source of truth: flag any ACTIVE hold whose
     * authorization is terminal (a dangling hold), and any account where the sum of ACTIVE holds does
     * not cross-foot the sum of AUTHORIZED authorization amounts. Neither side is re-derived from the
     * ledger's {@code balanceOf}, so a phantom/dangling hold is genuinely detected.
     */
    private CheckResult checkHoldConsistency(List<Offender> offenders) {
        List<DanglingHold> dangling = holdRecon.findDanglingHolds();
        List<HoldBalanceMismatch> mismatches = holdRecon.findHoldBalanceMismatches();
        if (dangling.isEmpty() && mismatches.isEmpty()) {
            return CheckResult.pass(
                    ReconCheck.HOLD_CONSISTENCY, "no dangling holds; ACTIVE holds cross-foot AUTHORIZED");
        }

        for (DanglingHold hold : capped(dangling)) {
            offenders.add(new Offender(
                    OffenderType.HOLD,
                    hold.holdId().toString(),
                    hold.currency(),
                    hold.amountMinor(),
                    "ACTIVE hold for %d minor units on account %s, but authorization %s is %s (dangling hold)"
                            .formatted(
                                    hold.amountMinor(),
                                    hold.accountId(),
                                    hold.authorizationId(),
                                    hold.authorizationStatus())));
            offenders.add(new Offender(
                    OffenderType.AUTHORIZATION,
                    hold.authorizationId().toString(),
                    hold.currency(),
                    0L,
                    "terminal %s authorization still owns ACTIVE hold %s"
                            .formatted(hold.authorizationStatus(), hold.holdId())));
        }

        long drift = 0L;
        for (HoldBalanceMismatch mismatch : capped(mismatches)) {
            long diff = mismatch.differenceMinor();
            drift += Math.abs(diff);
            offenders.add(new Offender(
                    OffenderType.ACCOUNT,
                    mismatch.accountId().toString(),
                    mismatch.currency(),
                    diff,
                    "ACTIVE holds (%d) do not cross-foot AUTHORIZED amounts (%d): off by %d minor units"
                            .formatted(mismatch.activeHoldMinor(), mismatch.authorizedAmountMinor(), diff)));
        }
        // A dangling hold always implies a cross-foot mismatch, so drift is already non-zero; guard the
        // degenerate case so the check never reports zero drift while failing.
        if (drift == 0L) {
            drift = dangling.stream().mapToLong(DanglingHold::amountMinor).sum();
        }
        return CheckResult.fail(
                ReconCheck.HOLD_CONSISTENCY,
                drift,
                "%d dangling hold(s), %d account cross-foot mismatch(es)"
                        .formatted(dangling.size(), mismatches.size()));
    }

    /** Outbox consistency: no PENDING event older than the configured stuck threshold. */
    private CheckResult checkOutboxConsistency(Instant ranAt, List<Offender> offenders) {
        Instant threshold = ranAt.minus(properties.getOutboxStuckAfter());
        long stuck = outboxRecon.countStuckPending(threshold);
        if (stuck == 0L) {
            return CheckResult.pass(ReconCheck.OUTBOX_CONSISTENCY, "no stuck PENDING outbox events");
        }
        List<StuckOutboxEvent> stuckEvents =
                outboxRecon.findStuckPending(threshold, properties.getMaxOffendersPerCheck());
        for (StuckOutboxEvent event : stuckEvents) {
            offenders.add(new Offender(
                    OffenderType.OUTBOX_EVENT,
                    String.valueOf(event.id()),
                    null,
                    0L,
                    "PENDING %s/%s appended at %s has not been relayed"
                            .formatted(event.aggregateType(), event.eventType(), event.occurredAt())));
        }
        // Outbox staleness is a reliability defect, not monetary drift: it fails the run but adds no
        // money to totalDriftMinor.
        return CheckResult.fail(ReconCheck.OUTBOX_CONSISTENCY, 0L, "%d stuck PENDING outbox event(s)".formatted(stuck));
    }

    // --- persistence mapping ---------------------------------------------------------------------

    private void persist(ReconciliationResult result) {
        try {
            String checksJson = objectMapper.writeValueAsString(result.checks());
            String offendersJson = objectMapper.writeValueAsString(result.offenders());
            results.save(new ReconciliationResultEntity(
                    result.id(),
                    result.ranAt(),
                    result.passed(),
                    result.totalDriftMinor(),
                    checksJson,
                    offendersJson,
                    summarize(result)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize reconciliation result " + result.id(), e);
        }
    }

    private ReconciliationResult toResult(ReconciliationResultEntity entity) {
        try {
            List<CheckResult> checks =
                    objectMapper.readValue(entity.getChecksJson(), new TypeReference<List<CheckResult>>() {});
            List<Offender> offenders =
                    objectMapper.readValue(entity.getOffendersJson(), new TypeReference<List<Offender>>() {});
            return new ReconciliationResult(
                    entity.getId(),
                    entity.getRanAt(),
                    entity.isPassed(),
                    entity.getTotalDriftMinor(),
                    checks,
                    offenders);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize reconciliation result " + entity.getId(), e);
        }
    }

    private static String summarize(ReconciliationResult result) {
        if (result.passed()) {
            return "PASS: zero drift across all checks";
        }
        Set<ReconCheck> failed = result.checks().stream()
                .filter(c -> !c.passed())
                .map(CheckResult::check)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return "FAIL: drift=%d failedChecks=%s offenders=%d"
                .formatted(result.totalDriftMinor(), failed, result.offenders().size());
    }

    private <T> List<T> capped(List<T> values) {
        int max = properties.getMaxOffendersPerCheck();
        return values.size() <= max ? values : values.subList(0, max);
    }
}
