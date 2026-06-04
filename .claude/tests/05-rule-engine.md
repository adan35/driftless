# Test cases — Spec 05 (Rule Engine)

QA verification of the hot-path rule engine: limits / velocity / MCC decisions, pre-compiled and
cached off the DB path, with a single-digit-millisecond p99 target. The engine moves no money; its
invariants are determinism + exactly-once velocity counting. Most cases are pure unit tests (no Spring
context, no datasource — itself the "no DB I/O on the hot path" proof). The p99 gate is an in-process
load test that runs under `verify`.

`db_config` is `n/a` for every row — the rule-engine hot path has NO datasource by construction
(`NoDatabaseOnHotPathTest` asserts the engine's Spring context contains zero `DataSource` beans).

| sr_no | test case (Given / When / Then) | expected | actual | reason_of_failure | db_config |
|------:|---------------------------------|----------|--------|-------------------|-----------|
| 1 | **`evaluate` performs no DB I/O (by construction).** Given the rule-engine module booted as a Spring context. When inspecting beans. Then there are ZERO `DataSource` beans, and `evaluate` still decides a clean context as APPROVE. | no datasource; evaluates | PASS — `NoDatabaseOnHotPathTest.theEngineContextHasNoDataSource`, `theEngineEvaluatesWithNoDatabasePresent` | — | n/a |
| 2 | **Over per-txn limit → DECLINE / LIMIT_EXCEEDED.** Given a $50.00 per-txn cap. When a $50.01 authorization. Then DECLINE, ruleId=`per-txn-50`, reasonCode=`LIMIT_EXCEEDED`. | DECLINE LIMIT_EXCEEDED | PASS — `RuleEvaluatorTest.overPerTransactionLimitDeclinesLimitExceeded` | — | n/a |
| 3 | **Exactly at per-txn limit → APPROVE (cap is a max).** Given a $50.00 cap. When exactly $50.00. Then APPROVE. | APPROVE | PASS — `RuleEvaluatorTest.exactlyAtPerTransactionLimitApproves` | — | n/a |
| 4 | **Exceed velocity count → DECLINE / VELOCITY.** Given max 3 in window, window holds 3. When the 4th. Then DECLINE, reasonCode=`VELOCITY`. | DECLINE VELOCITY | PASS — `RuleEvaluatorTest.exceedingVelocityCountDeclinesVelocity` | — | n/a |
| 5 | **Under velocity count → APPROVE.** Given max 3, window holds 2. When the 3rd. Then APPROVE. | APPROVE | PASS — `RuleEvaluatorTest.underVelocityCountApproves` | — | n/a |
| 6 | **Exceed velocity amount → DECLINE / VELOCITY.** Given $200 sum cap, window $150 + $60 now. Then DECLINE, reasonCode=`VELOCITY`. | DECLINE VELOCITY | PASS — `RuleEvaluatorTest.exceedingVelocityAmountDeclinesVelocity` | — | n/a |
| 7 | **Blocked MCC → DECLINE / MCC_BLOCKED.** Given a block list containing 7995. When MCC=7995. Then DECLINE, ruleId=`mcc-no-gambling`, reasonCode=`MCC_BLOCKED`. | DECLINE MCC_BLOCKED | PASS — `RuleEvaluatorTest.blockedMccDeclinesMccBlocked` | — | n/a |
| 8 | **Allow list declines unlisted MCC; approves listed.** Given allow list = {groceries}. When gambling MCC → MCC_BLOCKED; groceries → APPROVE. | deny unlisted | PASS — `RuleEvaluatorTest.allowListDeclinesUnlistedMcc` | — | n/a |
| 9 | **Periodic cap declines when projected spend exceeds cap.** Given $1000 daily cap, window $980 + $50 now. Then DECLINE, reasonCode=`LIMIT_EXCEEDED`. | DECLINE LIMIT_EXCEEDED | PASS — `RuleEvaluatorTest.periodicCapDeclinesWhenProjectedSpendExceedsCap` | — | n/a |
| 10 | **Clean transaction → APPROVE (ruleId/reason null).** Given a per-txn + MCC rule and a clean context. When evaluated. Then APPROVE, ruleId=null, reasonCode=null. | APPROVE clean | PASS — `RuleEvaluatorTest.cleanTransactionApproves` | — | n/a |
| 11 | **Deterministic: same context → same result.** Given a mixed rule set and one context. When evaluated 1000×. Then identical results. | deterministic | PASS — `RuleEvaluatorTest.evaluationIsDeterministicForTheSameContext` | — | n/a |
| 12 | **Lowest-priority rule wins when several would decline.** Given a per-txn (prio 50) and MCC (prio 10) both firing. When evaluated. Then MCC decides (lower priority value first). | priority order | PASS — `RuleEvaluatorTest.lowestPriorityRuleWinsWhenSeveralWouldDecline` | — | n/a |
| 13 | **Empty rule set approves everything.** Given no rules. When evaluated. Then APPROVE, 0 active rules. | APPROVE | PASS — `RuleEvaluatorTest.emptyRuleSetApprovesEverything` | — | n/a |
| 14 | **Rule change takes effect after refresh WITHOUT restart (tighten).** Given a $100 cap approving $50. When the cap is tightened to $40 on the same running engine and `refresh()` is called. Then the next evaluation declines $50 — no restart. | live tighten | PASS — `RuleCacheRefreshTest.tighteningALimitTakesEffectAfterRefreshWithoutRestart` | — | n/a |
| 15 | **Adding a rule at runtime takes effect after refresh.** Given an empty engine approving. When an MCC block is added and refreshed. Then the next evaluation declines MCC_BLOCKED. | live add | PASS — `RuleCacheRefreshTest.addingARuleAtRuntimeTakesEffectAfterRefresh` | — | n/a |
| 16 | **Failed compile keeps the previous good rule set.** Given a good $40 cap. When a duplicate-ruleId misconfiguration is added and refreshed (compile throws). Then `refresh()` returns false, the engine keeps the $40 cap (does NOT go empty/approve-all). | previous set retained | PASS — `RuleCacheRefreshTest.aFailedCompileKeepsThePreviousGoodRuleSet` | — | n/a |
| 17 | **Determinism property test (jqwik).** Given random contexts. When evaluated twice. Then results are equal (pure function). | deterministic over random | PASS — `RuleEngineDeterminismPropertyTest` (2) | — | n/a |
| 18 | **API contract / wiring tests.** Given the frozen `rules.api` types and Spring wiring. When exercised. Then the contract holds and the engine wires as a `RuleEngine` bean. | contract holds | PASS — `RuleApiContractTest` (6), `RuleEngineWiringTest` (5) | — | n/a |
| 19 | **Velocity: record then snapshot count+sum.** Given two records in window. When snapshot. Then count=2, sum=$35. | count+sum | PASS — `InMemoryVelocityStoreTest.recordsThenSnapshotsCountAndSum` | — | n/a |
| 20 | **Velocity: retried authorization counted exactly once.** Given the same authorization id recorded 3×. When snapshot. Then count=1, sum=$10 (2nd/3rd `record` return false). | counted once | PASS — `InMemoryVelocityStoreTest.retriedAuthorizationIsCountedExactlyOnce` | — | n/a |
| 21 | **Velocity: concurrent replays of the same id count once.** Given 32 threads recording the same id. When all run. Then exactly 1 winner, snapshot count=1. | once under concurrency | PASS — `InMemoryVelocityStoreTest.concurrentReplaysOfTheSameIdCountOnce` | — | n/a |
| 22 | **Velocity: window exclusion, per-account isolation, empty account.** Given events in/out of window and across accounts. When snapshot. Then only in-window survivors count; accounts isolated; unknown account empty. | windowing correct | PASS — `InMemoryVelocityStoreTest.eventsOutsideTheWindowAreExcluded`, `countsAreIsolatedPerAccount`, `unknownAccountSnapshotsEmpty` | — | n/a |
| 23 | **p99 of `evaluate` is single-digit ms (ENFORCED gate).** Given 5 representative rules and a 1,024-context seeded workload. When 50k warm-up + 500k measured calls are timed with `System.nanoTime()`. Then p99 < 10ms (build fails otherwise). **Re-run by QA — recorded p99 below.** | p99 < 10ms | PASS — `RuleEngineLatencyTest.p99IsSingleDigitMilliseconds`; **recorded this run: p50=0.0002ms p90=0.0005ms p99=0.0010ms p99.9=0.0133ms max=6.4479ms** (≈1 µs p99, ~10,000× under budget) | — | n/a |
| 24 | **[ADDED] Windowed-sum projection OVERFLOWS instead of declining at extreme velocity totals.** Given a velocity-amount rule and a window total of `Long.MAX_VALUE - 10`. When a positive amount is evaluated. Then `Math.addExact` throws `ArithmeticException` on the hot path (does NOT saturate to DECLINE). | ArithmeticException (current behavior pinned) | PASS — `RuleEvaluatorEdgeCaseTest.windowedSumProjectionOverflowsInsteadOfDecliningAtExtremeVelocityTotals` | — | n/a |
| 25 | **[ADDED] Periodic-cap projection overflows at extreme window spend.** Given a periodic cap and a window total of `Long.MAX_VALUE`. When evaluated. Then `ArithmeticException`. | ArithmeticException | PASS — `RuleEvaluatorEdgeCaseTest.periodicCapProjectionOverflowsAtExtremeWindowSpend` | — | n/a |
| 26 | **[ADDED] Zero-amount authorization approves under positive limits.** Given per-txn + periodic-cap rules. When amount=$0. Then APPROVE. | APPROVE | PASS — `RuleEvaluatorEdgeCaseTest.zeroAmountAuthorizationApprovesUnderPositiveLimits` | — | n/a |
| 27 | **[ADDED] Empty MCC is not blocked by a block list.** Given a block list. When MCC="". Then APPROVE (empty is not in the blocked set). | APPROVE | PASS — `RuleEvaluatorEdgeCaseTest.emptyMccIsNotBlockedByABlockList` | — | n/a |
| 28 | **[ADDED] Exactly at windowed-sum cap approves.** Given a $10.00 velocity-amount cap, window $9.99 + $0.01 now. Then APPROVE (cap is a max). | APPROVE | PASS — `RuleEvaluatorEdgeCaseTest.exactlyAtWindowedSumCapApproves` | — | n/a |
| 29 | **[ADDED] Equal-priority tie breaks on ruleId deterministically.** Given two per-txn limits at the same priority, both firing. When evaluated 100×. Then the lower ruleId (`aaa-limit`) always decides. | stable tie-break | PASS — `RuleEvaluatorEdgeCaseTest.equalPriorityTieBreaksOnRuleIdDeterministically` | — | n/a |

## p99 re-run evidence (orchestrator request)

`RuleEngineLatencyTest` was re-executed by QA as part of `verify`. Recorded log line from this run:

```
rule-engine evaluate latency over 500000 measured calls (5 rules):
  p50=0.0002ms p90=0.0005ms p99=0.0010ms p99.9=0.0133ms max=6.4479ms
```

**p99 ≈ 1 microsecond — about four orders of magnitude under the 10 ms budget.** The lone ~6.4 ms
`max` is a single GC/scheduler outlier over half a million calls (exactly why the gate targets p99,
not max). Single-digit-millisecond p99 confirmed with very large headroom on this machine
(Windows 11, JDK 21).

## Pass/fail summary

- **Total executed: 29 test cases** (44 underlying methods across `RuleEvaluatorTest`,
  `RuleCacheRefreshTest`, `RuleEngineDeterminismPropertyTest`, `RuleEngineLatencyTest`,
  `NoDatabaseOnHotPathTest`, `RuleApiContractTest`, `RuleEngineWiringTest`,
  `InMemoryVelocityStoreTest`, plus 6 added in `RuleEvaluatorEdgeCaseTest`).
- **Pass: 29 / Fail: 0.**
- The four decisions (LIMIT_EXCEEDED, VELOCITY, MCC_BLOCKED, APPROVE), determinism, no-DB-on-hot-path,
  live refresh, exactly-once velocity (incl. under concurrency), and the p99 gate are all independently
  verified.

## Prioritized issues (Spec 05)

- **Critical: none.** No money is moved; velocity is counted exactly once even under 32-thread
  concurrency; the hot path has no datasource the fault harness could stall.
- **Minor-1 (robustness — hot-path overflow):** `PeriodicSpendCap` and `VelocityAmount` project
  `velocityTotal + amount` with `Math.addExact`, which **throws `ArithmeticException`** on `long`
  overflow rather than declining (cases 24–25). `VelocitySnapshot` permits a total up to
  `Long.MAX_VALUE` (it only forbids negatives), so a pathological/garbage snapshot turns an otherwise
  total `evaluate` into an uncaught exception on the authorization hot path. Realistically unreachable
  with sane balances, but for a "never stalls/never crashes the decision path" guarantee, prefer
  **saturating** the projection (clamp to `Long.MAX_VALUE` → DECLINE) instead of throwing. Low
  likelihood, easy fix.
- **Nit (decline reason granularity):** `PeriodicSpendCap` and `VelocityAmount` both emit
  `LIMIT_EXCEEDED` / `VELOCITY` respectively, which is fine, but a periodic cap firing under the
  *velocity* rule type vs the *periodic-cap* type can only be told apart by `ruleId`, not `reasonCode`.
  Acceptable; just note for any downstream that buckets declines purely by `reasonCode`.
