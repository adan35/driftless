# Test Cases 06 — Tokenization Lifecycle (QA verification)

Independent QA verification of Spec 06 (`tokens` module). Build: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64`,
`./mvnw -B -pl tokens -am verify` and `./mvnw -B verify`. Integration tests use Testcontainers
`postgres:16-alpine` (real schema + Flyway + append-only trigger). **Docker required.**

**Result of the real run:** Full reactor `BUILD SUCCESS`. Tokens module **23 tests, 0 failures, 0 errors**
(`TokenLifecycleServiceIT` 12, `TokenControllerIT` 7, `TokenInvariantsIT` 4 — last is QA-authored).

`db_config` legend: **TC-PG** = Testcontainers Postgres 16-alpine, real `token` / `token_status_history` /
`idempotency_record` / `outbox_event` schema, fixed `Clock` = 2026-06-01T10:15:30Z.

| sr_no | test case (Given/When/Then) | expected | actual | reason_of_failure | db_config |
|-------|------------------------------|----------|--------|-------------------|-----------|
| 1 | **Given** a card ref **When** `create` **Then** token is INACTIVE, cardRef stored, 1 history row + 1 outbox event (`createYieldsInactiveTokenWithOneHistoryRowAndOneEvent`) | status INACTIVE; history=1; event=1 | PASS | — | TC-PG |
| 2 | **Given** an INACTIVE token **When** activate→suspend→resume→deactivate **Then** each lands in ACTIVE/SUSPENDED/ACTIVE/DEACTIVATED; 5 history rows + 5 events (`theFullLegalLifecycleAdvancesThroughEveryStateOneStepAtATime`) | terminal DEACTIVATED; history=5; event=5 | PASS | — | TC-PG |
| 3 | **Given** INACTIVE and SUSPENDED tokens **When** deactivate **Then** both → DEACTIVATED (`deactivateIsLegalFromInactiveAndFromSuspended`) | both DEACTIVATED | PASS | — | TC-PG |
| 4 | **Given** INACTIVE token **When** keyless `resume` **Then** `IllegalTokenTransition`; status, history(=1), event(=1) unchanged (`resumeFromInactiveIsIllegalAndChangesNothing`) | throws; nothing written | PASS | — | TC-PG |
| 5 | **Given** INACTIVE / SUSPENDED tokens **When** suspend-from-INACTIVE and activate-from-SUSPENDED **Then** both illegal, status unchanged (`suspendFromInactiveAndActivateFromSuspendedAreIllegal`) | both throw; unchanged | PASS | — | TC-PG |
| 6 | **Given** DEACTIVATED token **When** activate/suspend/resume **Then** all `IllegalTokenTransition`; no new history/event (terminal has no exit) (`nothingExitsTheTerminalDeactivatedState`) | all throw; counts frozen | PASS | — | TC-PG |
| 7 | **Given** unknown id **When** find / activate **Then** `TokenNotFound` (`findOnAnUnknownTokenThrowsTokenNotFound`) | throws TokenNotFound | PASS | — | TC-PG |
| 8 | **Given** an activated token **When** keyless activate again (already ACTIVE) **Then** no-op replay returns same result, no duplicate history/event (`aKeylessRetryAlreadyInTheTargetStateIsANoOpReplay`) | equal result; counts unchanged | PASS | — | TC-PG |
| 9 | **Given** explicit tokenId **When** keyless create twice **Then** second returns first, history=1, event=1 (`createWithAnExplicitIdIsIdempotent`) | idempotent; 1/1 | PASS | — | TC-PG |
| 10 | **Boundary: Given** create→activate→suspend→resume **When** a SECOND suspend **Then** it is a genuine NEW transition (not a false replay): status SUSPENDED, 5 history rows + 5 events (`suspendAfterResumeIsAGenuineNewTransitionNotAReplay`) | SUSPENDED; history=5; event=5 | PASS | — | TC-PG |
| 11 | **Given** create+activate **When** relay sweeps **Then** exactly 2 `TokenStatusChanged` delivered with from=null→INACTIVE and INACTIVE→ACTIVE (`transitionsPropagateAsTokenStatusChangedEvents`) | 2 events, correct from/to | PASS | — | TC-PG |
| 12 | **Given** a created token **When** inspect schema **Then** card_ref stored = ref (not PAN); no column named `pan` on `token` (`onlyTheCardReferenceIsStoredNeverARawPan`) | card_ref==ref; pan columns=0 | PASS | — | TC-PG |
| 13 | **Web: Given** create **When** activate then GET **Then** 200 ACTIVE, cardRef echoed (`createThenActivateReturnsTheUpdatedStatus`) | 200 ACTIVE | PASS | — | TC-PG |
| 14 | **Web: Given** POST /tokens without `Idempotency-Key` **Then** 400 (`aMutatingRequestWithoutAnIdempotencyKeyIs400`) | 400 | PASS | — | TC-PG |
| 15 | **Web idempotency: Given** same key + same body **When** create twice **Then** second replays original 201, no extra event (`aReplayUnderTheSameKeyReturnsTheOriginalResultAndWritesNothingNew`) | equal body; event count unchanged | PASS | — | TC-PG |
| 16 | **Web: Given** same key + DIFFERENT body **Then** 409 (`theSameKeyWithADifferentBodyIs409`) | 409 | PASS | — | TC-PG |
| 17 | **Boundary: Given** an ACTIVE token **When** resume under a FRESH key **Then** 422 (strict state machine), status stays ACTIVE (`aFreshIllegalTransitionIs422`) | 422; ACTIVE | PASS | — | TC-PG |
| 18 | **Web: Given** blank cardRef **Then** 400 validation (`anInvalidCreateBodyIs400`) | 400 | PASS | — | TC-PG |
| 19 | **Web: Given** unknown id **When** activate / GET **Then** 404 (`transitioningAnUnknownTokenIs404`) | 404 | PASS | — | TC-PG |
| 20 | **Invariant (QA): Given** a history row **When** raw `UPDATE token_status_history` **Then** DB trigger rejects with "append-only", row untouched (`theAppendOnlyTriggerRejectsUpdateOnTokenStatusHistory`) | DataAccessException; row unchanged | PASS | — | TC-PG |
| 21 | **Invariant (QA): Given** history rows **When** raw `DELETE token_status_history` **Then** DB trigger rejects with "append-only", count unchanged (`theAppendOnlyTriggerRejectsDeleteOnTokenStatusHistory`) | DataAccessException; count unchanged | PASS | — | TC-PG |
| 22 | **Idempotency (QA): Given** keyed activate **When** retried with the SAME key **Then** replays original, no duplicate history/event (`aKeyedActivateRetryReplaysAndWritesNothingNew`) | equal result; counts unchanged | PASS | — | TC-PG |
| 23 | **Atomicity (QA): Given** ACTIVE token **When** keyed resume (illegal) under fresh key **Then** `IllegalTokenTransition`, status/history/event unchanged AND idempotency claim rolled back (key row count=0) (`aKeyedIllegalTransitionRaisesAndRecordsNoKeyHistoryOrEvent`) | throws; nothing written; key not recorded | PASS | — | TC-PG |

## Coverage of Spec 06 acceptance criteria

| Criterion | Covered by | Verdict |
|-----------|-----------|---------|
| 1. create → INACTIVE | 1 | ✅ |
| 2. Each legal transition lands correctly | 2, 3 | ✅ |
| 3. Illegal transition rejected, changes nothing (no status/history/event change) | 4, 5, 6, 17, 23 | ✅ |
| 4. Exactly ONE history row per transition | 1, 2, 10 | ✅ |
| 5. Exactly ONE outbox event per transition, atomic with the write | 1, 2, 11, 23 | ✅ |
| 6. Idempotency (keyless state-anchored + keyed; same-key replay; same-key+diff-body ⇒ 409) | 8, 9, 10, 15, 16, 22 | ✅ |
| 7. Raw PAN never in storage/logs | 12 (+ code review: only `card_ref` persisted; no PAN in any log statement) | ✅ |
| 8. Append-only trigger rejects UPDATE/DELETE at the DB | 20, 21 | ✅ |

## Two-surface idempotency boundary (explicitly probed, as requested)

- Second suspend after resume = **NEW** transition, not a false replay — ✅ (case 10). Verified the
  keyless key derivation folds in `countByTokenId` (a monotonic per-token sequence), so
  `(transition, seq)` keys never recur; a re-applied transition of the same kind gets a distinct key.
- Keyed retry replays the original 2xx — ✅ (cases 15, 22).
- Fresh resume of an ACTIVE token under a NEW key ⇒ 422 illegal transition — ✅ (case 17).
- Note the documented asymmetry (intended, not a bug): the **keyless** surface treats a transition
  whose token is already in the target state as a safe no-op (e.g. keyless `resume` on an ACTIVE
  token returns ACTIVE), whereas the **keyed** web surface enforces the strict state machine and
  returns 422. See "Risks" R2.

## Risks / potential issues (not currently failing)

- **R1 (Medium) — no token-row lock; concurrent distinct legal transitions can double-write.**
  `apply()` reads `countByTokenId` outside the guard tx and there is no `SELECT … FOR UPDATE` / `@Version`
  on `TokenEntity`. The guard only serializes operations sharing the *same* key. Two concurrent legal
  transitions from the same state get *different* keys (e.g. keyless `suspend` → `…:SUSPEND:N`,
  keyless `deactivate` → `…:DEACTIVATE:N`; or two *keyed* calls with different `Idempotency-Key`s).
  Both can read the same live state, both pass the in-tx `isLegalFrom` check, and both commit — appending
  **two** history rows and **two** `TokenStatusChanged` events for what is logically one state, with
  last-writer-wins on `token.status`. No money moves, so not Critical, but it can corrupt the audit
  trail / emit a spurious propagation event. Suggested fix: pessimistic-lock the `token` row (or add
  `@Version` optimistic locking) inside the guarded supplier before re-checking the edge.
- **R2 (Low, by design) — keyless surface masks illegal "already-in-target" transitions as no-ops.**
  Keyless `resume`/`activate`/`deactivate` on a token already in the target state returns success
  instead of `IllegalTokenTransition` (line 168 short-circuit). Documented two-surface design; the
  keyed web surface correctly returns 422. Risk only if an internal caller depends on the strict
  rejection. Recommend the auth saga use the read endpoint / status check, not the keyless mutators.
- **R3 (Info) — atomicity depends on `DomainException` being unchecked.** Rollback of an illegal
  transition (and its idempotency claim) relies on the guard's `@Transactional` rolling back on a
  `RuntimeException`. Verified empirically by case 23 (the claim row count is 0 after rejection); kept
  as a regression anchor should `DomainException` ever be made checked.
