# Test Cases — Spec 03 Auth Lifecycle Saga (independent QA verification)

**Module:** `auth`
**Commands executed (real runs):**
- `./mvnw -B -pl auth -am verify` → BUILD SUCCESS, **41 tests** (reproduced developer's claim).
- Added `auth/src/test/java/io/driftless/auth/AuthFalsificationIT.java` (8 new probes).
- `./mvnw -B verify` (whole reactor, after `spotless:apply`) → **BUILD SUCCESS**, all modules green;
  recon `LedgerInvariantPropertyTest` (THE GATE) passed.
- Auth module total after additions: **49 tests, 0 failures, 0 errors.**
- Timing-sensitive tests (timeout / late-response) re-run **3×** — **no flakiness observed.**

**db_config (all integration tests):** Testcontainers `postgres:16-alpine` (JVM-wide singleton, Ryuk-reaped);
Flyway migrates all module histories (`db/migration`, `db/migration_idempotency`, `db/migration_tokens`,
`db/migration_auth`); `hibernate.ddl-auto=validate`; `open-in-view=false`. Partner leg = in-test
`ConfigurablePartnerServer` (random ephemeral port) with `auth.partner.read-timeout=PT0.4S`,
`connect-timeout=PT0.3S`; recovery `stuck-after=PT0S`, sweeps driven directly; rule `block-gambling`
blocks MCC `7995`. Unit test (`AuthorizationStatusTest`) needs no DB.

Legend for `actual`: ✅ = executed and passed; ⚠️ = executed, passed, but documents a defect/risk.

| sr_no | test case (Given/When/Then) | expected | actual | reason_of_failure | db_config |
|---|---|---|---|---|---|
| 1 | **Authorize places a hold** (AC1). Given a funded account (100k) and APPROVE partner; When authorize 30k; Then status AUTHORIZED, posted unchanged (100k), available −30k (70k), active hold 30k, global Σ=0. | available↓, posted unchanged, Σ=0 | ✅ pass (`AuthSagaIT.successfulAuthorizePlacesHold…`) | — | TC pg16, partner=APPROVE |
| 2 | **Capture settles & releases hold** (AC2). Given AUTHORIZED 30k; When capture full; Then CAPTURED, posted 70k, available 70k, hold released, Σ=0. | posted moves, hold released, Σ=0 | ✅ pass (`AuthSagaIT.captureConvertsHold…`) | — | TC pg16, partner=APPROVE |
| 3 | **Reverse from AUTHORIZED releases hold via compensation, no posted move** (AC3). Given AUTHORIZED; When reverse; Then REVERSED, posted 100k, available 100k, no new journal entries, Σ=0. | hold released, no posted move, Σ=0 | ✅ pass (`AuthSagaIT.reverseFromAuthorized…`) | — | TC pg16, partner=APPROVE |
| 4 | **Reverse of capture posts inverse as NEW tx** (AC3 immutability). Given CAPTURED; When reverse; Then REVERSED, posted restored 100k, exactly +1 new cardholder entry, Σ=0. | new compensating entry, Σ=0 | ✅ pass (`AuthSagaIT.reverseFromCaptured…`) | — | TC pg16, partner=APPROVE |
| 5 | **Rule DECLINE moves no money / no hold** (AC4). Given blocked MCC 7995; When authorize; Then DECLINED, posted/available 100k, hold 0. | no hold, no money | ✅ pass (`AuthSagaIT.ruleDeclineMovesNoMoney…`) | — | TC pg16 |
| 6 | **Partner TIMEOUT → compensating reversal, zero drift** (AC5). Given partner sleeps 2s > 0.4s read timeout; When authorize 30k; Then REVERSED, hold 0, available/posted 100k, Σ=0. | compensated, Σ=0 | ✅ pass (`AuthSagaIT.partnerTimeoutTriggersCompensating…`) | — | TC pg16, partner=TIMEOUT |
| 7 | **fail-before-response → compensate, absorb late work** (AC6). Given partner records partnerRef then 500; When authorize; Then REVERSED, hold 0, partner-side reversed=true, Σ=0. | late work absorbed, Σ=0 | ✅ pass (`AuthSagaIT.failBeforeResponseCompensates…`) | — | TC pg16, partner=FAIL_BEFORE_RESPONSE |
| 8 | **Inactive token declines, no hold** (AC10). Given token born INACTIVE; When authorize with tokenId; Then DECLINED reason TOKEN_INACTIVE, hold 0. | declined, no hold | ✅ pass (`AuthSagaIT.inactiveTokenDeclines…`) | — | TC pg16 |
| 9 | **Active token authorizes**. Given token activated; When authorize; Then AUTHORIZED, tokenId echoed. | authorized | ✅ pass (`AuthSagaIT.activeTokenIsAuthorized`) | — | TC pg16, partner=APPROVE |
| 10 | **Replay authorize same key → original, no 2nd hold** (AC7). When authorize twice same key; Then same id, no extra auth row, exactly one 30k hold. | idempotent replay | ✅ pass (`AuthSagaIT.replayingAuthorize…`) | — | TC pg16, partner=APPROVE |
| 11 | **Replay capture same key posts once** (AC7). When capture twice same key; Then posted unchanged after 2nd, 70k, Σ=0. | no double settlement | ✅ pass (`AuthSagaIT.replayingCapture…`) | — | TC pg16, partner=APPROVE |
| 12 | **Replay reverse same key idempotent** (AC7). When reverse twice same key; Then REVERSED, hold 0, posted 100k. | idempotent | ✅ pass (`AuthSagaIT.replayingReverse…`) | — | TC pg16, partner=APPROVE |
| 13 | **Partial capture settles only captured amount**. When capture 20k of 30k; Then posted 80k, hold 0, Σ=0. | partial settle | ✅ pass (`AuthSagaIT.partialCapture…`) | — | TC pg16, partner=APPROVE |
| 14 | **Partner DECLINE releases hold, no money**. Given partner DECLINE; When authorize; Then DECLINED, hold 0, balances 100k. | hold released | ✅ pass (`AuthEdgeCasesIT.partnerDecline…`) | — | TC pg16, partner=DECLINE |
| 15 | **Capture > authorized rejected**. When capture 50k of 30k; Then IllegalArgumentException, stays AUTHORIZED. | rejected | ✅ pass (`AuthEdgeCasesIT.captureForMoreThanAuthorized…`) | — | TC pg16, partner=APPROVE |
| 16 | **Capture of non-AUTHORIZED is conflict**. Given REVERSED; When capture; Then IllegalAuthorizationState. | illegal transition | ✅ pass (`AuthEdgeCasesIT.captureOfANonAuthorized…`) | — | TC pg16 |
| 17 | **Reverse of DECLINED is conflict**. Given DECLINED; When reverse; Then IllegalAuthorizationState. | illegal transition | ✅ pass (`AuthEdgeCasesIT.reverseOfADeclined…`) | — | TC pg16, partner=DECLINE |
| 18 | **Compensation still releases hold when partner reverse keeps failing** (AC9). Given FAIL_BEFORE_RESPONSE + reverseFails; When authorize; Then REVERSED, hold 0, Σ=0. | hold released anyway | ✅ pass (`AuthEdgeCasesIT.compensationStillReleases…`) | — | TC pg16 |
| 19 | **Reverse of AUTHORIZED w/o partnerRef still releases**. Given AUTHORIZED no partnerRef; When reverse; Then REVERSED, hold 0. | best-effort skip | ✅ pass (`AuthEdgeCasesIT.reverseOfAnAuthorizedHoldWithoutPartnerRef…`) | — | TC pg16 |
| 20 | **Capture currency mismatch rejected** (boundary). When capture EUR vs USD auth; Then IllegalArgumentException. | rejected | ✅ pass (`AuthEdgeCasesIT.captureWithMismatchedCurrency…`) | — | TC pg16, partner=APPROVE |
| 21 | **Reverse swallows failing partner reverse, still releases**. Given reverseFails; When reverse AUTHORIZED; Then REVERSED, hold 0. | hold released | ✅ pass (`AuthEdgeCasesIT.reverseSwallowsAFailingPartnerReverse…`) | — | TC pg16, partner=APPROVE |
| 22 | **Reverse AUTHORIZED with no active hold still terminal**. Given AUTHORIZED, no hold row; When reverse; Then REVERSED. | terminal | ✅ pass (`AuthEdgeCasesIT.reverseOfAnAuthorized…NoActiveHold…`) | — | TC pg16 |
| 23 | **AuthorizeCommand rejects non-positive amount**. When amount 0; Then IllegalArgumentException. | validated | ✅ pass (`AuthEdgeCasesIT.authorizeCommandRejectsNonPositive…`) | — | no DB |
| 24 | **HTTP authorize 201 with id+status**. | 201 AUTHORIZED | ✅ pass (`AuthControllerIT.authorizeReturns201…`) | — | TC pg16, RestTestClient |
| 25 | **Missing Idempotency-Key → 400** (AC7). | 400 | ✅ pass (`AuthControllerIT.mutatingRequestWithoutKey…`) | — | TC pg16 |
| 26 | **Same key + different body → 409** (AC7). | 409 | ✅ pass (`AuthControllerIT.sameKeyDifferentBodyIs409`) | — | TC pg16 |
| 27 | **Replay same key returns original (HTTP)**. | same authId | ✅ pass (`AuthControllerIT.replayWithSameKey…`) | — | TC pg16 |
| 28 | **Balance endpoint reflects holds + capture drives status**. GET balance posted 100k/available 70k; capture → CAPTURED; GET full state CAPTURED w/ partnerRef. | balances + state | ✅ pass (`AuthControllerIT.captureDrivesStatusAndBalance…`) | — | TC pg16 |
| 29 | **Reverse endpoint reverses**. POST reverse → REVERSED, hold 0. | reversed | ✅ pass (`AuthControllerIT.reverseEndpoint…`) | — | TC pg16 |
| 30 | **Unknown authorization GET → 404**. | 404 | ✅ pass (`AuthControllerIT.unknownAuthorizationGetIs404`) | — | TC pg16 |
| 31 | **Authorize unknown token → 404**. | 404 | ✅ pass (`AuthControllerIT.authorizeWithUnknownTokenIs404`) | — | TC pg16 |
| 32 | **Authorize unknown account → 422** (ledger domain error). | 422 | ✅ pass (`AuthControllerIT.authorizeAgainstUnknownAccountIs422`) | — | TC pg16 |
| 33 | **Invalid body (blank currency) → 400**. | 400 | ✅ pass (`AuthControllerIT.invalidBodyIs400`) | — | TC pg16 |
| 34 | **HTTP capture > authorized → 400**. | 400 | ✅ pass (`AuthControllerIT.captureForMoreThanAuthorizedIs400`) | — | TC pg16 |
| 35 | **HTTP capture after reverse → 409**. | 409 | ✅ pass (`AuthControllerIT.captureAfterReverseIs409`) | — | TC pg16 |
| 36 | **RecoverySweep: stuck AUTHORIZING never confirmed → compensated** (AC8). Insert AUTHORIZING+hold, partner unknown; sweep → REVERSED, hold 0, available 100k, Σ=0. | no dangling hold | ✅ pass (`RecoverySweepIT.sweepCompensatesAStuckAuthorizing…`) | — | TC pg16 |
| 37 | **RecoverySweep: stuck AUTHORIZING partner approved → AUTHORIZED** (AC8). | hold stands | ✅ pass (`RecoverySweepIT.sweepFinalizes…Approved`) | — | TC pg16, partner=APPROVE |
| 38 | **RecoverySweep: stuck AUTHORIZING partner declined → DECLINED, hold released** (AC8). | hold released | ✅ pass (`RecoverySweepIT.sweepReleases…Declined`) | — | TC pg16, partner=DECLINE |
| 39 | **RecoverySweep: stuck COMPENSATING re-driven → REVERSED** (AC9). | re-driven terminal, Σ=0 | ✅ pass (`RecoverySweepIT.sweepReDrives…Compensating`) | — | TC pg16 |
| 40 | **Unit: terminal states = DECLINED/CAPTURED/REVERSED**. | classified | ✅ pass (`AuthorizationStatusTest`) | — | no DB |
| 41 | **Unit: in-flight states = AUTHORIZING/COMPENSATING**. | classified | ✅ pass (`AuthorizationStatusTest`) | — | no DB |
| 42 | **[NEW F1] Immutability byte-identical**. Given CAPTURED; capture entries snapshot for cardholder+settlement; When reverse; Then every pre-existing JournalEntry is still present & `equals` (containsAll), +1 new entry per account, Σ=0. | originals untouched | ✅ pass (`AuthFalsificationIT.reverseOfCaptureLeavesOriginalEntriesByteIdentical`) | — | TC pg16, partner=APPROVE |
| 43 | **[NEW F2] Double capture under DIFFERENT keys rejected (no double settlement)**. Given CAPTURED via key A; When capture again key B; Then IllegalAuthorizationState, posted unchanged 70k, Σ=0. | no double-effect | ✅ pass (`AuthFalsificationIT.secondCaptureUnderADifferentKey…`) | — | TC pg16, partner=APPROVE |
| 44 | **[NEW F3] Double reverse-of-capture under DIFFERENT keys rejected**. Given REVERSED via key A; When reverse key B; Then IllegalAuthorizationState, posted stays 100k, Σ=0. | no double inverse | ✅ pass (`AuthFalsificationIT.secondReverseOfCapturedUnderADifferentKey…`) | — | TC pg16, partner=APPROVE |
| 45 | **[NEW F4] Capture same key + different amount → IdempotencyConflict (409 contract)**. Given captured 20k via key K; When capture 10k same key K; Then IdempotencyConflict, only 20k settled (80k), Σ=0. | conflict, single effect | ✅ pass (`AuthFalsificationIT.captureSameKeyDifferentAmountConflicts`) | — | TC pg16, partner=APPROVE |
| 46 | **[NEW F5] Zero-amount capture rejected (boundary)**. When capture 0; Then IllegalArgumentException, stays AUTHORIZED, hold 30k intact. | rejected | ✅ pass (`AuthFalsificationIT.zeroAmountCaptureIsRejected`) | — | TC pg16, partner=APPROVE |
| 47 | **[NEW F6] Blank/empty/null Idempotency-Key rejected on authorize+capture+reverse**. | IllegalArgumentException each | ✅ pass (`AuthFalsificationIT.blankIdempotencyKeyIsRejectedOnEveryMutatingStep`) | — | TC pg16, partner=APPROVE |
| 48 | **[NEW F7] THE GATE — mixed sequence keeps global Σ=0**. Sequence: capture-full, partial-capture, reverse-from-authorized, capture+reverse, injected TIMEOUT→REVERSED, rule-decline; Then `globalSignedSumMinor(USD)==0` and account active-hold total 0. | Σ=0, no dangling hold | ✅ pass (`AuthFalsificationIT.mixedRandomishSequenceKeepsGlobalSignedSumZero`) | — | TC pg16, mixed partner modes |
| 49 | **[NEW F8] ⚠️ Late partner success after a TIMEOUT — absorption probe**. Given partner sleeps 1.5s then APPROVES & records partnerRef; When authorize (times out)→REVERSED, Σ=0; wait 2s for partner to record; Then probe partner-side reversed. **Observed `reversed==false`** ⇒ the late partner approval is NOT reversed on the partner side. Local ledger Σ stays 0 (test asserts that), but the partner-side authorization is left active. | local Σ=0; partner-side absorption gap documented | ⚠️ pass (asserts the gap: `partnerSideReversed==false`) | Documents **Issue #1** below: timeout path does not absorb a *late* partner success (only fail-before-response does). | TC pg16, partner=TIMEOUT |

## Coverage of spec acceptance criteria (all executed)

| AC | Criterion | Covered by sr_no |
|----|-----------|------------------|
| 1 | authorize places hold (available↓, posted unchanged) | 1, 28 |
| 2 | capture settles + releases hold | 2, 13, 28 |
| 3 | reverse via compensating entry, originals unchanged | 3, 4, 42 |
| 4 | rule DECLINE moves no money / no hold | 5 |
| 5 | partner timeout → compensating reversal, zero drift | 6, 48 |
| 6 | fail-before-response → compensate, late success absorbed | 7 (✅); **timeout variant ⚠️ 49** |
| 7 | every step idempotent; same-key=original; diff-body=409; missing=400 | 10,11,12,25,26,27,45,47 |
| 8 | process-kill recovery (no dangling hold / half-posted capture) | 36,37,38,39 |
| 9 | compensation retried to completion (cannot silently fail) | 18,39 (✅ local); see Issue #2 for partner-leg caveat |
| 10 | token gating: non-ACTIVE declines, no hold | 8 |
| GATE | random seq Σ journal entries == 0 | 48 + recon `LedgerInvariantPropertyTest` |

## Notes on invariant scope verified
- **Balance:** verified via `globalSignedSumMinor(USD)==0` after every money-moving sequence (sr 1–7,
  11,13,42–45,48). Per-account journal sum is *not* expected to be 0 in double-entry (it equals the
  account's posted balance); the correct global invariant is asserted instead, matching the spec/ledger.
- **Immutability:** sr 42 proves pre-existing `JournalEntry` rows are byte-identical (record-equality)
  after a reversal; reversal only appends. The ledger V2 trigger blocks UPDATE/DELETE (Spec 01).
- **Idempotency:** same-key replay (10–12), same-key/diff-body conflict (26,45), missing/blank key
  (25,47), and double-effect under *different* keys blocked by FOR-UPDATE state re-check (43,44).
