# Review 08 — Observability & Dashboard

## GATE: PASS

No invariant is breached. The `@Primary` decorators delegate the frozen contracts faithfully and add
no I/O or control-flow changes; the outbox fan-out preserves at-least-once delivery and cannot lose
or duplicate events; all metric labels are bounded (no PAN / account-id / token-id cardinality); and
the headline `driftless_recon_drift_amount` gauge is fed from the real `ReconciliationResult`, not a
hardcoded 0. The findings below are hardening (Warning) and notes (Info) — none block the gate.

---

## Regression-safety verification (highest priority — all PASS)

- **`MeteredLedger`** implements the frozen `Ledger` and delegates **all five** methods
  (`openAccount/post/balanceOf/findTransaction/entriesFor`) unchanged
  (`MeteredLedger.java:44-74`). `post` counts the accepted path after the delegate returns and
  re-throws `BalanceInvariantViolation` after counting — no swallowed exceptions, no altered
  semantics, counters never gate control flow. Idempotency/balance/immutability all live in the
  delegate and are untouched. ✔
- **`TimedRuleEngine`** returns the delegate's `RuleResult` unchanged and only wraps a nanosecond
  `Timer` sample in a `finally` (`TimedRuleEngine.java:40-49`). No DB/lock/I-O added to the hot path.
  The histogram makes p99 visible per spec. ✔
- **Delegate selection** is by explicit `@Qualifier` bean name (`ObservabilityMetricsConfig.java:31,37`),
  and the verified `@Service` default names are `ledgerService` (`LedgerService.java:61-63`) and
  `ruleEvaluator` (`RuleEvaluator.java:30-32`) — primary decorator + qualified delegate ⇒ **no
  self-injection cycle**. ✔
- **Frozen shapes unchanged.** `ledger.api.Ledger` is byte-identical; only **new** SPI surface is
  added: `LedgerReconView.entryCount()`, `OutboxReconView.countPending()`, new
  `auth.spi.SagaMetricsView`, new `outbox.spi.OutboxPublication`/`OutboxPublicationListener`. No
  `UPDATE`/`DELETE` on `journal_entry` anywhere. ✔
- **GATE unaffected.** `observability` is downstream of every flow module and nothing depends on it
  (`observability/pom.xml`, `app/pom.xml:49`), so the recon invariant property test runs without the
  decorators in scope — the metered path is an `app`-only composition. ✔
- **Outbox delivery integrity.** The listener is invoked in id order, inside the publish transaction,
  *before* `markPublished` (`OutboxBatchPublisher.java:64-74`); a default no-op listener is always
  present (`IdempotencyAutoConfiguration.java:45-48`); and `OutboxEventMetrics` dedups on the
  persisted event id with a bounded (50k) LRU set (`OutboxEventMetrics.java:45,56-61,84`). Unit test
  proves redelivery does not double-count (`MetricsUnitTest.java:79-84`). ✔
- **No high-cardinality labels.** Tags are `outcome/check/decision/reason/status/transition/
  aggregate_type/event_type` — all bounded enums or machine reason codes
  (`RuleResult` reasonCode ∈ {LIMIT_EXCEEDED, VELOCITY, MCC_BLOCKED, …}). No id/PAN label. ✔

---

## Critical
None.

---

## Warning

### W1 — Relay batch can be wedged by a throwing observation listener
`idempotency/.../OutboxBatchPublisher.java:67-73`
The `publicationListener.onPublished(...)` call runs **inside the relay transaction and is
unguarded**. The SPI contract says listeners "must not throw" (`OutboxPublicationListener.java:15-21`),
and today's `OutboxEventMetrics` won't (counter increments + a try/catch'd JSON parse), but if any
future or misbehaving listener throws (e.g. registry/OOM/NPE), the whole batch rolls back, the rows
stay `PENDING`, and a deterministic failure becomes a redelivery loop that stalls *money-adjacent*
event delivery. Observation must never be able to break delivery.
**Fix:** wrap the call defensively so a listener fault is logged and swallowed, keeping delivery on
the happy path:
```java
try {
    publicationListener.onPublished(new OutboxPublication(...));
} catch (RuntimeException ex) {
    log.warn("outbox publication listener failed for event {} — continuing delivery", event.getId(), ex);
}
```

### W2 — Recon result event is published synchronously inside the GATE transaction
`recon/.../ReconciliationService.java:113` (`eventPublisher.publishEvent(result)` inside the
`@Transactional run()`), consumed by `ReconMetrics.onReconciliation` (`ReconMetrics.java:78-79`).
A plain `@EventListener` runs **synchronously in the same transaction**, so an exception thrown while
updating the gauges would propagate and **roll back the just-persisted `reconciliation_result`** —
an observer aborting the reconciliation run that is the public proof of the gate. (Reachable today
only if `stuckCountFrom` hit a null `detail`; see I4 — currently the detail string is always
non-null, so this is latent, not live.)
**Fix:** decouple observation from the recon transaction with
`@TransactionalEventListener(phase = AFTER_COMMIT)` on `onReconciliation`, or guard the listener body
so it can never propagate. AFTER_COMMIT also makes the gauge reflect only persisted runs.

### W3 — Per-call meter registration on the rule-engine hot path
`observability/.../TimedRuleEngine.java:51-58`
`count(...)` calls `Counter.builder(...).tag(...).tag(...).register(registry)` on **every**
`evaluate()`. That allocates a `Tags` list and does a `ConcurrentHashMap` lookup per call on the
single-digit-ms p99 path the spec explicitly wants to protect. The decision/reason space is small and
bounded.
**Fix:** resolve the counters once (e.g. cache by `decision+reason` in a small `ConcurrentHashMap`
or pre-register the known reason codes in the constructor) and just `increment()` the cached `Counter`
in `evaluate()`. The `Timer` is already cached this way — mirror it.

---

## Info

- **I1 — Counters increment before commit (overcount on rollback).** `MeteredLedger.post` increments
  after the inner delegate returns (`MeteredLedger.java:48`) and `OUTBOX_PUBLISHED` increments before
  `markPublished` commits (`OutboxBatchPublisher.java:74`). If an enclosing/own transaction later
  rolls back, the counter has already moved. Acceptable for a monotonic throughput counter, but worth
  noting the metric is "attempted/observed", not "committed".
- **I2 — Dedup set is in-memory only.** `OutboxEventMetrics.counted` (`OutboxEventMetrics.java:56`)
  does not survive a process restart, so the javadoc/comment ("a crash-driven redelivery never
  double-counts", `OutboxBatchPublisher.java:65`) is slightly overstated. It's harmless because the
  Micrometer counters also reset on restart, so post-restart redelivery counts from the same zero
  baseline. Consider softening the comment.
- **I3 — Delegate bean names are hardcoded strings.** `ObservabilityMetricsConfig.java:24,27`
  couples to `"ledgerService"`/`"ruleEvaluator"`. This fails fast (context won't start) if a
  `@Service` is renamed, but a typed marker/qualifier annotation would be less brittle. Low priority.
- **I4 — `ReconMetrics.stuckCountFrom` couples to a message string and assumes non-null detail.**
  `ReconMetrics.java:108-119` parses the leading integer from `CheckResult.detail()`
  (`"%d stuck PENDING outbox event(s)"`, `ReconciliationService.java:291`). `detail` is non-null in
  all current paths, but `detail.indexOf(' ')` would NPE if a future `fail(...)` passed null — and via
  W2 that NPE would abort the recon tx. Add a null guard, or surface the stuck count as a structured
  field on `CheckResult` instead of re-parsing prose.
- **I5 — App IT only asserts the green path.** `DriftlessApplicationTests.java:152-160` proves
  drift→0 / passed→1 end-to-end, but the **red** path (drift>0 ⇒ gauge nonzero, `run_passed`→0) is
  only covered at the unit level (`MetricsUnitTest.java:55-64`). The spec gate hinges on the dashboard
  going red under the deliberate-drift fixture; consider an app-level assertion that a failing run
  flips `driftless_recon_run_passed` to 0 and `driftless_recon_drift_amount` nonzero.

---

## Checklist coverage
1. Regression safety — **PASS** (faithful delegation, no hot-path I/O, no cycle, frozen shapes intact; W3 perf nit).
2. Outbox fan-out — **PASS** with **W1** hardening (isolate listener from delivery).
3. Metric honesty — **PASS** (drift gauge from real result; gauges bound to live suppliers/atomics; dashboard references the real metric).
4. Multi-module Flyway — **PASS** (distinct history tables, no cross-location scan, baseline-0 over populated schema, `ddl-auto=validate`, app-only — does not leak into module tests; verified by `everyModuleFlywayTimelineIsApplied`).
5. Conventions/security — **PASS** (constructor injection, SLF4J `{}`, no secrets in `deploy/`, datasource `editable:false`, dashboards `allowUiUpdates:false`, no PAN labels, observability imports only published api/spi).
6. Performance — mostly PASS; **W3** is the one hot-path allocation/lookup to fix. Gauges hold strong refs to singleton beans (no GC surprise).
