package io.driftless.observability.metrics;

/**
 * The canonical Micrometer meter names for Driftless, all under the {@code driftless.} prefix (dots
 * become underscores in the Prometheus exposition, e.g. {@code driftless_recon_drift_amount}). One
 * source of truth so the dashboards-as-code and the instrumentation never drift apart.
 */
public final class MetricNames {

    private MetricNames() {}

    /** Common tag keys. */
    public static final String TAG_OUTCOME = "outcome";

    public static final String TAG_CHECK = "check";
    public static final String TAG_DECISION = "decision";
    public static final String TAG_REASON = "reason";
    public static final String TAG_STATUS = "status";
    public static final String TAG_TRANSITION = "transition";
    public static final String TAG_AGGREGATE_TYPE = "aggregate_type";
    public static final String TAG_EVENT_TYPE = "event_type";

    // --- reconciliation (the zero-drift headline) ------------------------------------------------
    /** Gauge: headline monetary drift in minor units. Reads {@code 0} for a correct ledger. */
    public static final String RECON_DRIFT_AMOUNT = "driftless.recon.drift.amount";

    /** Gauge: {@code 1} when the latest run passed, {@code 0} when it failed. */
    public static final String RECON_RUN_PASSED = "driftless.recon.run.passed";

    /** Gauge: epoch-seconds of the latest reconciliation run. */
    public static final String RECON_LAST_RUN = "driftless.recon.last.run.timestamp";

    /** Gauge: stuck PENDING outbox events the latest run measured. */
    public static final String RECON_STUCK_OUTBOX = "driftless.recon.stuck.outbox.count";

    /** Gauge (tagged {@code check}): {@code 1}/{@code 0} per individual reconciliation check. */
    public static final String RECON_CHECK_PASSED = "driftless.recon.check.passed";

    /** Counter (tagged {@code outcome=pass|fail}): reconciliation runs observed. */
    public static final String RECON_RUNS = "driftless.recon.runs";

    // --- rule engine (the p99 claim) -------------------------------------------------------------
    /** Timer with a percentile histogram over {@code RuleEngine.evaluate} — makes p99 visible. */
    public static final String RULES_EVALUATION = "driftless.rules.evaluation";

    /** Counter (tagged {@code decision}, {@code reason}): rule decisions by reason code. */
    public static final String RULES_DECISIONS = "driftless.rules.decisions";

    // --- ledger ----------------------------------------------------------------------------------
    /** Counter: balanced posts accepted by the ledger (postings/sec via {@code rate()}). */
    public static final String LEDGER_POSTINGS = "driftless.ledger.postings";

    /** Counter: posts rejected with {@code BalanceInvariantViolation} (nothing written). */
    public static final String LEDGER_REJECTED = "driftless.ledger.rejected.unbalanced";

    /** Gauge: total committed {@code journal_entry} rows. */
    public static final String LEDGER_ENTRY_COUNT = "driftless.ledger.entry.count";

    /** Gauge: Σ over currencies of |signed sum| in minor units — another zero-drift signal ({@code 0}). */
    public static final String LEDGER_SIGNED_SUM_ABS = "driftless.ledger.signed.sum.abs.minor";

    // --- auth saga -------------------------------------------------------------------------------
    /** Counter: authorizations approved (the partner approved and the hold stands). */
    public static final String AUTH_AUTHORIZATIONS = "driftless.auth.authorizations";

    /** Counter: authorizations declined (rule / token / partner). */
    public static final String AUTH_DECLINES = "driftless.auth.declines";

    /** Counter: captures settled into a posted transaction. */
    public static final String AUTH_CAPTURES = "driftless.auth.captures";

    /** Counter: compensating (and explicit) reversals — rises under fault injection while drift stays 0. */
    public static final String AUTH_COMPENSATING_REVERSALS = "driftless.auth.compensating.reversals";

    /** Gauge: compensations finalized REVERSED without a confirmed partner reverse (must be {@code 0}). */
    public static final String AUTH_DANGLING_PARTNER_REVERSE = "driftless.auth.dangling.partner.reverse";

    /** Gauge: authorizations still {@code COMPENSATING} (outstanding partner reverses). */
    public static final String AUTH_OUTSTANDING_OBLIGATIONS = "driftless.auth.outstanding.partner.obligations";

    /** Gauge (tagged {@code status}): saga state distribution. */
    public static final String AUTH_SAGA_STATE = "driftless.auth.saga.state";

    // --- outbox ----------------------------------------------------------------------------------
    /** Gauge: PENDING outbox events awaiting relay (queue depth / relay health). */
    public static final String OUTBOX_PENDING_DEPTH = "driftless.outbox.pending.depth";

    /** Counter (tagged {@code aggregate_type}, {@code event_type}): events delivered by the relay. */
    public static final String OUTBOX_PUBLISHED = "driftless.outbox.published";

    // --- tokens ----------------------------------------------------------------------------------
    /** Counter (tagged {@code transition}): token status changes, e.g. {@code ACTIVE_to_SUSPENDED}. */
    public static final String TOKENS_STATUS_CHANGES = "driftless.tokens.status.changes";
}
