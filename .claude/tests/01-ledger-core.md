# Test Cases — Spec 01 Ledger Core

**Module under test:** `ledger` (frozen `io.driftless.ledger.api` / `spi` contract + `internal` implementation)
**Gate:** QA verification of the immutable double-entry ledger ("provable zero drift" foundation).
**How run:** independent `./mvnw.cmd clean verify` at the repo root, Docker engine v28.3.0 up.
**Testcontainers image (all integration + property tests):** `postgres:16-alpine` (server reported `16.14`).

## Verbatim build result (authoritative final run, QA supplement included)

```
[INFO] Reactor Summary for driftless-parent 0.0.1-SNAPSHOT:
[INFO] driftless-parent ................................... SUCCESS [  3.787 s]
[INFO] common ............................................. SUCCESS [ 22.467 s]
[INFO] ledger ............................................. SUCCESS [01:58 min]
[INFO] idempotency ........................................ SUCCESS [  2.628 s]
[INFO] auth ............................................... SUCCESS [  2.065 s]
[INFO] rules .............................................. SUCCESS [  1.796 s]
[INFO] tokens ............................................. SUCCESS [  2.270 s]
[INFO] recon .............................................. SUCCESS [  6.101 s]
[INFO] observability ...................................... SUCCESS [  1.443 s]
[INFO] app ................................................ SUCCESS [ 48.421 s]
[INFO] partner-simulator .................................. SUCCESS [ 18.366 s]
[INFO] BUILD SUCCESS
[INFO] Total time:  03:49 min
```

`ledger` JaCoCo gate (LINE >= 0.85, BRANCH >= 0.80 over `io.driftless.ledger.internal.*`): **All coverage checks have been met** (bundle = 21 classes). Spotless format gate: passed.

## Test counts (per class; surefire = *Test unit/property, failsafe = *IT integration)

| class | phase | run | fail | err | skip | time |
|---|---|---|---|---|---|---|
| `ledger.api.PostingLineTest` | surefire | 4 | 0 | 0 | 0 | 1.185 s |
| `ledger.api.PostingRequestTest` | surefire | 5 | 0 | 0 | 0 | 0.154 s |
| `ledger.LedgerZeroDriftPropertyTest` | surefire | 2 | 0 | 0 | 0 | 37.70 s |
| `ledger.LedgerServiceIT` | failsafe | 14 | 0 | 0 | 0 | 8.341 s |
| `ledger.LedgerHoldViewIT` | failsafe | 1 | 0 | 0 | 0 | 35.51 s |
| `ledger.LedgerQaSupplementIT` (QA-authored) | failsafe | 5 | 0 | 0 | 0 | 6.751 s |
| `recon.LedgerInvariantPropertyTest` (Spec 07 stub) | surefire | 1 | 0 | 0 | **1 (Disabled, expected)** | 0.155 s |
| `app.DriftlessApplicationTests` (contextLoads) | failsafe | 1 | 0 | 0 | 0 | 35.98 s |

**Ledger module:** surefire 11 (skip 0), failsafe 20 (skip 0) → **31 ledger tests, all green.**
Property `sumOfAllJournalEntriesIsZeroPerCurrency`: **tries = 30, checks = 30** (non-vacuous, real Postgres posts).

## Case table

| sr_no | test case (Given/When/Then) | expected | actual | reason_of_failure | db_config |
|---|---|---|---|---|---|
| 1 | **Given** two USD accounts **When** a balanced 2-line transfer is posted **Then** exactly 1 `transaction` row + 2 `journal_entry` rows exist and `replayed=false`, `postedAt`=fixed clock | 1 tx, 2 entries, not replayed | PASS — `balancedTwoLinePostProducesTwoEntriesAndOneTransaction` | — | postgres:16-alpine |
| 2 | **Given** two USD accounts **When** a 5.00 debit vs 4.00 credit is posted **Then** `BalanceInvariantViolation` is thrown and `transaction.count()`/`journal_entry.count()` are unchanged | typed reject, 0 rows written | PASS — `unbalancedPostIsRejectedAndWritesNothing` | — | postgres:16-alpine |
| 3 | **Given** a USD account and a EUR account **When** a USD-denominated line targets the EUR account **Then** `AccountCurrencyMismatch` is thrown and no entries are written | typed reject, 0 rows | PASS — `postIsRejectedWhenLineCurrencyDiffersFromAccountCurrency` | — | postgres:16-alpine |
| 4 | **Given** one known account **When** a line references a non-existent account **Then** `AccountNotFound` is thrown | typed reject | PASS — `postIsRejectedWhenAccountDoesNotExist` | — | postgres:16-alpine |
| 5 | **Given** a posted tx under key `replay-key` **When** the same key+body is posted again **Then** the original `TxId` is returned with `replayed=true` and row counts before==after (0 new rows) | original TxId, replayed, 0 new rows | PASS — `replayWithSameKeyReturnsOriginalTxIdAndWritesNoNewRows` | — | postgres:16-alpine |
| 6 | **Given** an account id **When** `openAccount` is called twice with the same id **Then** the same `Account` is returned and exactly 1 `account` row exists | idempotent, 1 row | PASS — `openAccountIsIdempotentOnCallerSuppliedId` | — | postgres:16-alpine |
| 7 | **Given** two posts of 10.00 and 4.00 to an asset **When** `balanceOf` is read **Then** asset posted=+14.00, liability posted=-14.00, and with no holds `available==posted` | summation, available==posted | PASS — `balanceOfEqualsSummationAndAvailableEqualsPostedWithNoHolds` | — | postgres:16-alpine |
| 8 | **Given** an unknown account id **When** `balanceOf` is called **Then** `AccountNotFound` is thrown (not null) | typed reject | PASS — `balanceOfUnknownAccountIsRejected` | — | postgres:16-alpine |
| 9 | **Given** a posted tx **When** `findTransaction` is called **Then** the immutable view returns idempotencyKey, occurredAt, postedAt and entries ordered seq 0,1 | immutable view, ordered | PASS — `findTransactionReturnsTheImmutableViewWithOrderedEntries` | — | postgres:16-alpine |
| 10 | **Given** a random `TxId` **When** `findTransaction` is called **Then** `TransactionNotFound` is thrown (not null) | typed not-found | PASS — `findTransactionThrowsTypedExceptionWhenNotFound` | — | postgres:16-alpine |
| 11 | **Given** 3 posts to an asset **When** `entriesFor(page0,size2)` and `(page1,size2)` are read **Then** page0 has 2 entries, page1 has 1, all for the account | paging works | PASS — `entriesForPagesAnAccountsEntriesOldestFirst` | — | postgres:16-alpine |
| 12 | **Given** an original post of 9.00 **When** a reversing balanced post (opposite directions) is applied **Then** both balances net to 0 and 4 immutable rows survive (2 original + 2 compensating) | correction = new post, originals intact | PASS — `correctionIsANewCompensatingPostNotAMutation` | — | postgres:16-alpine |
| 13 | **Given** a committed journal entry **When** a direct SQL `UPDATE journal_entry ...` is issued **Then** the DB trigger rejects it with "append-only" | DB-level reject (UPDATE) | PASS — `directUpdateOnJournalEntryIsRejectedByTheTrigger` | — | postgres:16-alpine |
| 14 | **Given** a committed journal entry **When** a direct SQL `DELETE FROM journal_entry` is issued **Then** the DB trigger rejects it with "append-only" | DB-level reject (DELETE) | PASS — `directDeleteOnJournalEntryIsRejectedByTheTrigger` | — | postgres:16-alpine |
| 15 | **Given** a `HoldView` stub reporting a 3.00 active hold **When** `balanceOf` is read after a 10.00 post **Then** posted=10.00 and `available == posted - 3.00 == 7.00` | available = posted − holds | PASS — `availableIsPostedMinusActiveHoldTotal` | — | postgres:16-alpine |
| 16 | **Given** a fresh ledger **When** 30 randomized sequences (1–8 posts each, random magnitudes, 3 currencies) are posted **Then** for every currency the global signed `SUM(journal_entry)` is exactly 0 | global sum == 0 per currency, all tries | PASS — `LedgerZeroDriftPropertyTest.sumOfAllJournalEntriesIsZeroPerCurrency`, **tries=30 checks=30** | — | postgres:16-alpine |
| 17 | **Given** jqwik on the classpath **When** the engine-sanity property runs **Then** it executes (proves the engine is live, not vacuously green) | engine runs, tries=5 | PASS — `LedgerZeroDriftPropertyTest.engineSanityCheck`, tries=5 | — | n/a (pure jqwik) |
| 18 | **Given** the Spec 07 recon stub **When** the reactor runs **Then** `recon.LedgerInvariantPropertyTest` remains `@Disabled` (Skipped:1) and untouched by Spec 01 | stays skipped, unmodified | PASS — Skipped:1; `git diff HEAD -- recon` empty | — | n/a |
| 19 | **Given** the app monolith now requires a datasource **When** `app.DriftlessApplicationTests.contextLoads` boots against Testcontainers Postgres **Then** the context loads green | context loads | PASS — app contextLoads, 35.98 s | — | postgres:16-alpine |
| 20 | **Given** `PostingLine` **When** constructed with amount 0 / negative / null component **Then** `IllegalArgumentException("strictly positive")` / `NullPointerException` | record guard rejects | PASS — `PostingLineTest` (4 cases) | — | n/a (pure unit) |
| 21 | **Given** `PostingRequest` **When** constructed with blank/null key, null occurredAt, <2 lines, or a later-mutated list **Then** rejected / defensively copied | record guards reject; immutable copy | PASS — `PostingRequestTest` (5 cases) | — | n/a (pure unit) |
| 22 | **(QA)** **Given** three USD accounts **When** a balanced 5-leg post (>2 lines, incl. a self-cancelling pair) is posted **Then** 5 `journal_entry` rows with dense seq 0..4 and correct net balances | 5 entries, seq 0-4, balances correct | PASS — `LedgerQaSupplementIT.balancedFourLinePostProducesFourEntries` | — | postgres:16-alpine |
| 23 | **(QA)** **Given** one account **When** a single-leg `PostingRequest` is constructed **Then** the frozen record guard rejects "at least 2 lines" and no rows are written | reject, 0 rows | PASS — `LedgerQaSupplementIT.singleLineRequestIsRejectedBeforeAnyWrite` | — | postgres:16-alpine |
| 24 | **(QA RISK PROBE)** **Given** a posted tx 5.00 under key `reuse-key` **When** the SAME key is posted with a DIFFERENT body (8.00) **Then** the ledger returns the ORIGINAL TxId with `replayed=true`, writes no new rows, and the balance still reflects 5.00 (new body silently ignored) | original returned, body ignored, 0 new rows | PASS (behaviour PINNED) — `LedgerQaSupplementIT.usedKeyWithDifferentBodyReturnsOriginalAndIgnoresNewBody`; logs: `post committed key=reuse-key ... replayed=false` then `post replay key=reuse-key txId=7f4f...　replayed=true` | — see Issue M1 | postgres:16-alpine |
| 25 | **(QA)** **Given** 3 posts (3.00, 7.00, and a 2.00 reversing post) **When** balances are read **Then** asset=+8.00, card=-8.00 and asset+card sum == 0 (balance is signed summation, never stored) | derived summation, cross-account sum 0 | PASS — `LedgerQaSupplementIT.postedBalanceIsTheSignedSumOfManyEntries` | — | postgres:16-alpine |
| 26 | **(QA)** **Given** an empty line list **When** a `PostingRequest` is constructed **Then** "at least 2 lines" reject and no rows written | reject, 0 rows | PASS — `LedgerQaSupplementIT.emptyLineListIsRejectedByTheRecordGuard` | — | postgres:16-alpine |

## Pass/fail summary

- **Surefire (unit + property):** common 22, ledger 11, recon 1 (Skipped:1 expected), idempotency/auth/rules/tokens/observability scaffolding 0 → all green.
- **Failsafe (integration):** ledger 20 (14 + 1 + 5 QA), app 1, partner-simulator 1 → all green.
- **Ledger total: 31 tests, 0 failures, 0 errors, 0 unexpected skips.**
- **Reactor: BUILD SUCCESS, 11/11 modules SUCCESS.** No drift, no UPDATE/DELETE reaching `journal_entry`, no double-effect observed.

## Acceptance-criteria traceability (Spec 01 — each independently executed)

| Spec 01 criterion | proven by | result |
|---|---|---|
| unbalanced rejected (typed) + writes nothing | case 2 | PASS |
| line currency != account rejected | case 3 | PASS |
| balanced 2-line → 2 entries + 1 transaction | case 1 | PASS |
| replay same key → original TxId, replayed, no new rows | case 5 | PASS |
| balanceOf == summation; available==posted (no holds) | cases 7, 25 | PASS |
| HoldView → available == posted − activeHoldTotal | case 15 | PASS |
| no UPDATE/DELETE code path; DB rejects both at runtime | cases 13, 14 (separate UPDATE + DELETE) | PASS |
| global SUM(journal entries)==0 via random valid sequence (jqwik) | case 16 (tries=30) | PASS |
| integration tests on real Postgres (Testcontainers) | all *IT + property | PASS |

## Prioritized issue list

**Critical:** none. No drift (every property try summed to zero), no mutation/deletion of settled history reached `journal_entry` (DB trigger rejects both), no double-effect on replay.

**Major:** none.

**Minor / risks to close downstream:**

- **M1 (risk, NOT a Spec 01 failure) — used idempotency key + different body silently returns the original.** `LedgerService.post` looks up by `idempotencyKey` first and returns the stored result without comparing the request body (case 24 pins this: a conflicting 8.00 body under a reused key returned the original 5.00 transaction and applied nothing). Per the brief, enforcing `409-on-different-body` is Spec 02's REST machinery, so the gate is NOT failed for it — but it must be closed by Spec 02 (hash/compare the request payload against the stored key). Until then a caller that accidentally reuses a key with a different intent gets a silent no-op, which is hard to notice. **Logging is adequate to detect it** (`post replay key=... replayed=true` is emitted), but the log does not record that the *body differed*; Spec 02 should log a key/body-conflict at WARN.

- **M2 (low-likelihood, lightly covered) — concurrent duplicate-key race fallback.** The `catch (DataIntegrityViolationException)` branch in `post` that resolves a lost unique-key race is exercised only indirectly; there is no concurrent (two-thread) test that forces two simultaneous posts under one key. The unique index `ux_transaction_idempotency_key` + single-thread replay test give reasonable confidence the invariant holds (the DB is the final arbiter), and the branch logs `post lost idempotency race key=... winningTxId=... replayed=true`, so a race is diagnosable. Recommend Spec 02 add an explicit concurrent test when it generalises idempotency. No evidence of a defect today.

- **M3 (observability nit) — `balanceOf` / `entriesFor` log at DEBUG only.** Saga-step tracing for posts is solid at INFO (`post committed ...`, `post replay ...`, `opened account ...`). Reads are DEBUG, which is fine, but reconciliation in Spec 07 may want an INFO/structured hook. Not a Spec 01 gap.

## Scope / freeze verification

- **Frozen contract preserved.** `git diff HEAD` touches only `PostingLine.java` and `PostingRequest.java` in `api`; both record headers are byte-identical (`record PostingLine(AccountId account, Direction direction, Money amount)` and `record PostingRequest(String idempotencyKey, Instant occurredAt, String description, List<PostingLine> lines)`). Changes are additive compact-constructor guards only (positive amount; non-blank key / non-null occurredAt / >=2 lines / defensive copy). No component, type, accessor, or method signature changed across `api`/`spi`. **No freeze breach.**
- **`app` change is test-only and clean.** `app/pom.xml` adds three **test-scope** Testcontainers deps; `DriftlessApplicationTests` adds a test-only `@ServiceConnection` Postgres container. `DriftlessApplication` main and `app/src/main/resources/application.properties` are unchanged (no production datasource committed — that is Spec 09's concern). `contextLoads` passes against Testcontainers (case 19).
- **recon stub untouched** (`git diff HEAD -- recon` empty; remains `@Disabled`, Skipped:1).
- **QA-added file:** `ledger/src/test/java/io/driftless/ledger/LedgerQaSupplementIT.java` (test source only; no production/contract/recon modification).
