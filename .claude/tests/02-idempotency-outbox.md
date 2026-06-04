# Test cases — Spec 02 (Idempotency & Outbox)

QA verification of the idempotency replay guard and transactional outbox. Invariant 3 (idempotency)
is implemented system-wide here, so this is the deepest-verified of the Wave-1 batch. Every row below
was executed against **real PostgreSQL via Testcontainers** (`postgres:16-alpine`) under Failsafe
(`*IT`) or as a pure unit test (`*Test`) under Surefire. Build command:
`.\mvnw.cmd -pl idempotency,partner-simulator,rules -am verify` (Windows, Docker 28.x).

Legend for `db_config`: `tc:postgres:16-alpine` = Testcontainers Postgres 16 alpine; `n/a` = pure
unit test, no datasource.

| sr_no | test case (Given / When / Then) | expected | actual | reason_of_failure | db_config |
|------:|---------------------------------|----------|--------|-------------------|-----------|
| 1 | **Concurrent same-key → one side effect.** Given two callers race `guard.execute` with the SAME key + same body, each posting a ledger transfer. When both run on a 2-thread pool released by a barrier. Then exactly one `transaction` row exists, exactly one `idempotency_record`, the side-effect counter is 1, both callers get the same value, and exactly one is `replayed=true`. | 1 tx, 1 record, both values equal, one fresh one replay | PASS — `IdempotencyConcurrencyIT.twoConcurrentRequestsWithSameKeyProduceOneSideEffectAndSameResponse` | — | tc:postgres:16-alpine |
| 2 | **Replay after simulated crash.** Given a committed first call (ledger transfer posted), the process "dies" before returning. When the caller retries with the same key+body. Then the retry is `replayed=true`, returns the original value, the operation body runs exactly once, and NO new `journal_entry` rows are written. | original result, 0 new ledger rows, op ran once | PASS — `IdempotencyGuardIT.retryAfterCommitReturnsOriginalResultAndRunsNoNewSideEffect` | — | tc:postgres:16-alpine |
| 3 | **Typed-result replay round-trips.** Given a `record Receipt` result stored as JSON. When replayed (operation throws if run). Then the replay reconstructs the typed record from `response_blob`+`response_type` and equals the original. | typed value rebuilt, op not run | PASS — `IdempotencyGuardIT.replayReconstructsTheTypedRecordResultFromTheStoredBlob` | — | tc:postgres:16-alpine |
| 4 | **Same key + different body → 409/conflict.** Given a key first used with `body-hash-original`. When reused with `body-hash-DIFFERENT`. Then `IdempotencyConflict` is thrown carrying (key, expected, actual) and the operation does NOT run. | IdempotencyConflict, op not run | PASS — `IdempotencyGuardIT.sameKeyWithDifferentRequestHashIsAConflict` | — | tc:postgres:16-alpine |
| 5 | **Blank key / blank requestHash rejected before work.** Given a blank key or blank requestHash. When `execute` is called. Then `IllegalArgumentException` naming the offending field, no DB write. | IllegalArgumentException | PASS — `IdempotencyGuardIT.blankKeyIsRejected`, `blankRequestHashIsRejected` | — | tc:postgres:16-alpine |
| 6 | **Outbox atomic with ledger — rollback.** Given a `@Transactional` method posts a ledger transfer AND appends an outbox event, then throws. When it rolls back. Then neither the `outbox_event` row nor the `journal_entry` rows survive (counts unchanged). | 0 outbox rows, 0 ledger rows | PASS — `OutboxAtomicityIT.rollbackOfTheSurroundingTransactionLeavesNoOutboxRowAndNoLedgerRows` | — | tc:postgres:16-alpine |
| 7 | **Outbox atomic with ledger — commit (positive control).** Given the same method without the throw. When it commits. Then exactly one outbox row and two journal entries are added. | +1 outbox, +2 ledger | PASS — `OutboxAtomicityIT.commitOfTheSurroundingTransactionPersistsBothTheLedgerRowsAndTheOutboxRow` | — | tc:postgres:16-alpine |
| 8 | **Append outside a transaction is rejected.** Given `OutboxWriter.append` called with no active transaction (`Propagation.MANDATORY`). When invoked. Then an `IllegalTransactionStateException`/`UnexpectedRollbackException` — no non-atomic write. | exception, no write | PASS — `OutboxAtomicityIT.appendOutsideATransactionIsRejected` | — | tc:postgres:16-alpine |
| 9 | **Relay crash between deliver and mark → exactly-once.** Given two events; a crash is armed on the 2nd (delivered to consumer, then its batch rolls back). When the relay sweeps (throws), then restarts and sweeps again. Then no row stays PENDING, the 2nd event was delivered ≥2 times (at-least-once), but an id-keyed idempotent consumer observed each event exactly once in order. | no loss, no observable duplicate | PASS — `OutboxRelayIT.eventDeliveredButNotMarkedIsRepresentedAndObservedExactlyOnceAfterRestart` | — | tc:postgres:16-alpine |
| 10 | **Relay preserves per-aggregate ordering.** Given 5 events on one aggregate appended in one tx. When the relay sweeps. Then the idempotent consumer observes them in append/id order. | ascending id order | PASS — `OutboxRelayIT.relayPreservesPerAggregateOrdering` | — | tc:postgres:16-alpine |
| 11 | **Relay properties defaulting (unit).** Given null/explicit `OutboxRelayProperties`. When constructed. Then defaults are 500ms / 100 / 50 and explicit values are kept. | correct defaults | PASS — `OutboxRelayPropertiesTest` (2) | — | n/a |
| 12 | **Default publisher logs (unit).** Given the `LoggingEventPublisher`. When publishing. Then it logs id/aggregate/eventType via SLF4J placeholders (no `System.out`). | logged, no throw | PASS — `LoggingEventPublisherTest` | — | n/a |
| 13 | **[ADDED] Thrown operation does not poison the key.** Given a guarded op that posts a ledger transfer then throws. When `execute` runs. Then the exception propagates, the claim is rolled back (0 `idempotency_record` rows for the key — no IN_PROGRESS tombstone, no phantom COMPLETED), and 0 ledger rows committed. | claim+ledger rolled back, key free | PASS — `IdempotencyOperationFailureIT.aThrownOperationRollsBackTheClaimAndTheLedgerLeavingTheKeyRetryable` | — | tc:postgres:16-alpine |
| 14 | **[ADDED] Failed op → same key retried succeeds exactly once.** Given a first attempt that throws after posting. When the SAME key is retried with a succeeding op, then replayed once more. Then the retry is a FRESH execution (not replay), commits exactly +2 ledger rows, the op body ran twice total (fail+success), and the later replay returns the stored value with 0 new rows. | retried once, then replayed, no double-post | PASS — `IdempotencyOperationFailureIT.afterAFailedOperationTheSameKeySucceedsExactlyOnceOnRetry` | — | tc:postgres:16-alpine |
| 15 | **[ADDED] Mid-batch publish failure rolls back the WHOLE batch.** Given 3 events in one batch (batch-size=10), the publish of the middle event fails. When the relay sweeps (throws). Then all 3 rows are still PENDING with `attempts` reset to 0 (no half-commit), the failed batch delivered [E0,E1]; after retry all land, the de-duplicated first-seen delivery order is ascending (E0<E1<E2 — no inversion), and E0 (an earlier event than the failed one) is delivered TWICE. | atomic rollback, ordering preserved, E0 redelivered once | PASS — `OutboxBatchFailureIT.aMidBatchPublishFailureRollsBackTheWholeBatchSoNoEarlierEventIsLeftPublished` | — | tc:postgres:16-alpine |
| 16 | **[ADDED] `attempts` not durably counted across a rolled-back batch (observability flag).** Given a 2-event batch where the 2nd fails. When the relay sweeps (throws) then retries. Then immediately after failure both rows are PENDING with `attempts=0`, and after the successful retry both end PUBLISHED with `attempts=1` — the failed attempt left NO durable trace. | attempts {0,0} then {1,1} | PASS — `OutboxBatchFailureIT.rolledBackBatchDoesNotDurablyCountFailedAttempts` | — | tc:postgres:16-alpine |
| 17 | **[ADDED] Per-aggregate ordering with interleaved aggregates in one batch.** Given A and B events interleaved (ids interleave globally) in one tx. When the relay sweeps. Then global delivery equals append/id order, and each aggregate's own slice (A0<A1<A2, B0<B1) is delivered in order. | per-aggregate order holds | PASS — `OutboxBatchFailureIT.perAggregateOrderingHoldsWhenTwoAggregatesAreInterleavedInOneBatch` | — | tc:postgres:16-alpine |

## Risk probe requested by the orchestrator — batch-failure double-publish / ordering / `attempts`

> "Does the relay's batch-failure path ever deliver a later event ahead of an earlier one, or lose
> the `attempts`/status accounting in a way that could double-publish to a NON-idempotent consumer?"

**Findings (from the verified diagnostic + tests 15–17):**

- **Ordering — SAFE.** `OutboxBatchPublisher.publishBatch` is one `@Transactional` method that processes
  the locked batch strictly in `id ASC` order and **publish-then-mark in the same transaction**. A
  publish failure throws, aborting the whole batch (all rows revert to PENDING, `attempts` reverts to
  0). On retry the relay re-claims from the lowest pending id, so the de-duplicated first-seen delivery
  order is always ascending — a later event is **never** applied before an earlier one. The raw
  at-least-once stream for the example is `[E0, E1, E0, E1, E2]`.
- **Double-publish to a NON-idempotent consumer — REAL, BY DESIGN, MITIGATED BY CONTRACT.** Because the
  whole batch rolls back, **earlier events in a failed batch are re-delivered** on retry (E0 is
  delivered twice when E1 fails). This is correct at-least-once behaviour, but it means a consumer that
  is **not** idempotent would double-apply E0. Spec 02 explicitly requires id-keyed idempotent
  consumers; the seam (`EventPublisher` + `PublishedOutboxEvent.id()`) supports it and the relay never
  promises exactly-once delivery, only effectively-once via dedup. **Not a defect**, but a sharp edge
  that MUST be honored by every consumer the `app`/Spec 03 wires in (see Minor-1).
- **`attempts` accounting — CORRECT for safety, but an observability blind spot.** `recordAttempt()`
  lives inside the batch transaction, so a failed delivery's increment is rolled back with everything
  else. Rows therefore show `attempts == 1` even when delivery was actually attempted twice. No
  correctness impact (status is the source of truth), but `attempts` cannot be used to detect a poison
  event that keeps failing — flag for Spec 07/08 monitoring (Minor-2).

## Pass/fail summary

- **Total executed: 17 test cases** (39 underlying test methods across the module's `*IT`/`*Test`
  classes — 14 pre-existing, 5 added across 2 new methods… counted as cases above).
- **Pass: 17 / Fail: 0.** (Two of the added cases initially failed because the *test's* assumptions
  about ordering and `attempts` were wrong, not the production code; corrected after a verified
  diagnostic — see notes. The corrected assertions reflect the real, safe behavior.)
- Every integration case ran on Testcontainers Postgres 16; no in-memory substitute.

## Prioritized issues (Spec 02)

- **Critical: none.** No drift, no double-effect, no double-publish that the documented contract does
  not already require consumers to absorb.
- **Minor-1 (consumer-contract sharp edge):** the outbox is at-least-once and re-delivers earlier
  events in a rolled-back batch. Every `EventPublisher`/consumer downstream MUST dedup by
  `PublishedOutboxEvent.id()`. Suggest a CI guard (or a documented checklist) when Spec 03 wires a real
  consumer, and consider isolating each event in its own transaction (batch-size=1 semantics per
  event) if redelivery of unrelated earlier events is undesirable.
- **Minor-2 (observability):** `attempts` does not durably count failed delivery attempts (rolled back
  with the batch), so it cannot surface a stuck/poison event. Suggest either incrementing attempts in a
  separate committed step before publish, or adding a dedicated failure metric in Spec 08.
- **Nit:** the `replayExisting` "committed as IN_PROGRESS → retry" branch is defensive and unreachable
  in the single-transaction model (acknowledged in code + the jacoco 0.70 branch floor). Correct to
  keep; just noting it is asserted only by construction, not by a test.
