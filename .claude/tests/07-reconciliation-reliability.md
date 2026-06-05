# Test Cases — Spec 07 Reconciliation & Reliability (THE GATE)

> Independent QA verification. Real runs executed on Java 21 (`/usr/lib/jvm/java-21-openjdk-arm64`),
> Docker + Testcontainers Postgres `postgres:16-alpine`. Commands:
> `./mvnw -B -pl recon -am clean verify` (run 1), `./mvnw -B -pl recon -am verify` (runs 2–3),
> `./mvnw -B verify` (full reactor). All four builds reported **BUILD SUCCESS**.
>
> One test was ADDED by QA: `HoldConsistencyProbeIT` (adversarial probe of the HOLD_CONSISTENCY
> check). Recon module test count: **20** (4 surefire + 16 failsafe). Full reactor green.

## Pass/fail summary (from the real run)

| Suite | Tests | Result |
|-------|-------|--------|
| LedgerInvariantPropertyTest (THE GATE) | 2 (`randomSaga…` ×15 tries + `engineSanityCheck` ×5) | PASS |
| DriftDetectionIT | 3 | PASS |
| ReconciliationServiceIT | 4 | PASS |
| FaultInjectionIT | 4 | PASS |
| LoadGeneratorIT | 2 | PASS |
| ReconciliationControllerIT | 2 | PASS |
| FaultInjectionHarnessTest | 2 | PASS |
| HoldConsistencyProbeIT (QA-added) | 1 | PASS |
| **Recon total** | **20** | **PASS** |
| Full reactor (common→…→partner-simulator) | all modules | BUILD SUCCESS |

Flakiness: 3 consecutive recon `verify` runs (plus 1 earlier) → 4 green runs; the property ran with
**different random seeds each run** (e.g. `1312825703521749897`, `949196106276607297`,
`-6003205844442914531`, …) — ≥60 randomized property executions, **zero failures, no flakiness**.

## Test cases (Given / When / Then)

| sr_no | test case (Given/When/Then) | expected | actual | reason_of_failure | db_config |
|-------|------------------------------|----------|--------|-------------------|-----------|
| 01 | GIVEN the jqwik engine is wired / WHEN `engineSanityCheck` runs / THEN the property engine executes (guards against the gate being silently skipped) | engine executes 5 tries | PASS (5 checks) | — | n/a (no DB) |
| 02 | GIVEN real Ledger+auth saga & controllable partner injecting APPROVE/DECLINE/TIMEOUT/FAIL_BEFORE_RESPONSE / WHEN a random sequence of authorize/capture/reverse settles via recovery sweep / THEN global signed journal sum == 0 per currency | no drift | PASS (15 tries, seeds varied per run) | — | Testcontainers PG 16, real Flyway (5 histories), saga in-process |
| 03 | GIVEN a settled random sequence / WHEN every executed op is replayed with its ORIGINAL idempotency key / THEN journal row count and authorization count are unchanged | no double-effect | PASS | — | as 02 |
| 04 | GIVEN a settled random sequence / WHEN holds are inspected / THEN every AUTHORIZED auth has exactly 1 ACTIVE hold, every terminal auth has none, and available == posted − activeHoldTotal | clean holds, no dangling | PASS | — | as 02 |
| 05 | GIVEN a settled random sequence / WHEN the recon job runs / THEN `reconciliationService.run().passed()` is true | recon agrees zero drift | PASS | — | as 02 |
| 06 | GIVEN no AUTHORIZING/COMPENSATING should remain / WHEN sequence settles / THEN both in-flight counts are zero | no stuck in-flight | PASS | — | as 02 |
| 07 | GIVEN a correct ledger / WHEN an UNBALANCED journal_entry is inserted via direct SQL (bypassing post()) / THEN recon reports FAILED, GLOBAL_ZERO fails, driftMinor == 12_345 | detector fires with exact amount | PASS | — | Testcontainers PG, `@SpringBootTest` |
| 08 | GIVEN injected drift / WHEN recon runs / THEN offenders name the offending TRANSACTION and its ACCOUNT | RCA names offender | PASS | — | as 07 |
| 09 | GIVEN a failed reconciliation / WHEN RcaReporter.report() runs / THEN report is drifted, lists failed checks, candidate cause contains "unbalanced", names TRANSACTION+ACCOUNT | RCA report producible | PASS | — | as 07 |
| 10 | GIVEN injected drift detected / WHEN @AfterEach runs / THEN drift is corrected by appending a NEW compensating CREDIT (never UPDATE/DELETE of journal_entry); raw ledger still shows the injected sum during the test | immutability respected | PASS | — | as 07 |
| 11 | GIVEN a clean ledger / WHEN RcaReporter.report() runs on a passing run / THEN report states "no drift" / "PASSED" | passes on clean data | PASS | — | as 07 |
| 12 | GIVEN funded account / WHEN reconciliationService.run() on demand / THEN a persisted PASSING result with all 4 checks, exposed via latest() | on-demand + persistence | PASS | — | as 07 |
| 13 | GIVEN the scheduler / WHEN scheduledRun() invoked twice / THEN two distinct persisted result rows | runs on schedule + persists | PASS | — | as 07 |
| 14 | GIVEN a clean load run (40 ops, APPROVE) / WHEN it settles / THEN recon reports zero drift and globalSignedSum == 0 | NO-fault ⇒ zero drift | PASS | — | as 07 |
| 15 | GIVEN an APPROVE authorize emits a PENDING outbox event with stuck-after=0 / WHEN recon runs / THEN OUTBOX_CONSISTENCY fails, an OUTBOX_EVENT offender is named, money checks still hold | stuck-outbox detection | PASS | — | as 07 |
| 16 | GIVEN partner TIMEOUT for 4 authorizes / WHEN they compensate and settle / THEN zero drift, activeHoldTotal==0, available restored | timeout self-heals | PASS | — | as 07 |
| 17 | GIVEN partner FAIL_BEFORE_RESPONSE (work done, 500) / WHEN compensation runs / THEN auth REVERSED, partner.reversed()==true, zero drift (late/duplicate side-effect absorbed) | fail-before-response self-heals | PASS | — | as 07 |
| 18 | GIVEN duplicate authorize+capture with SAME keys / WHEN replayed / THEN exactly one settlement posted (posted==950_000), zero drift | idempotency, no double-effect | PASS | — | as 07 |
| 19 | GIVEN a crash residue (AUTHORIZING + ACTIVE hold, never confirmed) inserted directly / WHEN recovery sweep runs / THEN auth REVERSED, activeHoldTotal==0, zero drift | process-kill recovery | PASS | — | as 07 |
| 20 | GIVEN a clean account / WHEN load profile (60 ops, mix) runs / THEN totalOperations==60, failed==0, approvals+declines+captures/reversals positive, throughput>0, p50≤p99≤max latency reported | load gen throughput+latency | PASS | — | as 07 |
| 21 | GIVEN 20 ops throttled to ~40/s on 1 worker / WHEN run / THEN throughput respected (≤60/s), elapsed≥400ms, zero drift | configurable rate honoured | PASS | — | as 07 |
| 22 | GIVEN drift over HTTP / WHEN POST /reconciliation/run, GET latest, GET {id}, GET {id}/rca / THEN failing result served, totalDrift==4_321, RCA cause "unbalanced" | REST surface for dashboard | PASS | — | `@AutoConfigureRestTestClient`, PG |
| 23 | GIVEN unknown id / WHEN GET /reconciliation/{id} and /rca / THEN 404 | negative path | PASS | — | as 22 |
| 24 | GIVEN harness drives control plane / WHEN inject(timeout/fail/duplicate/decline/errorRate/latency), reset, resetAll / THEN 6 well-formed FaultProfile bodies + 2 control hits | fault-harness wire contract | PASS | — | in-test HTTP stub (no DB) |
| 25 | **(QA-added) ADVERSARIAL** GIVEN an AUTHORIZED auth flipped to REVERSED with its hold left ACTIVE (a dangling hold) / WHEN recon runs / THEN HOLD_CONSISTENCY still PASSES (tautological), but an ACTIVE hold on a terminal auth exists (which the property gate's assertCleanHolds WOULD flag) | document HOLD_CONSISTENCY blind spot | PASS (check passed despite dangling hold — see Issue #2) | — | Testcontainers PG, `@SpringBootTest` |

## DB config notes

- Every integration test uses a real **Testcontainers PostgreSQL 16-alpine** with all module Flyway
  histories migrated (`ddl-auto=validate`, `open-in-view=false`).
- Schedulers/relays disabled on wall-clock (`poll-delay=PT1H`); recovery sweep/recon driven directly.
- `auth.recovery.partner-grace=PT3S` ≥ partner TIMEOUT sleep (2s); read-timeout `PT0.4S`. Settle
  budget = 8s. Observed stable across all runs.
