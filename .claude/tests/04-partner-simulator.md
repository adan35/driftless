# Test cases — Spec 04 (Partner Simulator)

QA verification of the standalone partner simulator: a real HTTP network boundary with injectable
latency/failure/timeout and a runtime control plane. **It holds no money or ledger logic** — verified
by construction (no ledger dependency in its POM; amounts/currencies are opaque pass-through fields).
HTTP tests boot the service on a random port via `RestTestClient` and drive it exactly as Spec 03 /
Spec 07 will. Unit tests for the fault registry use no Spring context.

`db_config` is `n/a` for every case — the simulator has no database (its state is an in-memory store).
The "boundary" is HTTP, asserted by `@SpringBootTest(webEnvironment = RANDOM_PORT)`.

| sr_no | test case (Given / When / Then) | expected | actual | reason_of_failure | db_config |
|------:|---------------------------------|----------|--------|-------------------|-----------|
| 1 | **Builds as its own boot jar / own port, independent of `app`.** Given the `partner-simulator` module with its own `main` + random-port web env. When the context boots. Then it starts standalone with no `app` on the classpath. | standalone boot OK | PASS — `PartnerSimulatorApplicationTests` (context loads on RANDOM_PORT) | — | n/a |
| 2 | **No faults → authorize approves and is idempotent on request id.** Given no fault profile. When `POST /partner/authorize` twice with the same request id. Then first approves with a partner ref; the replay echoes the exact same response. | approve + idempotent replay | PASS — `PartnerHappyPathTest.authorizeApprovesAndIsIdempotent` | — | n/a |
| 3 | **No faults → capture idempotent.** Given a capture request id. When posted twice. Then both responses equal, partnerRef echoed. | idempotent | PASS — `PartnerHappyPathTest.captureConfirmsAndIsIdempotent` | — | n/a |
| 4 | **No faults → reverse idempotent.** Given a reverse request id. When posted twice. Then both responses equal, partnerRef echoed. | idempotent | PASS — `PartnerHappyPathTest.reverseConfirmsAndIsIdempotent` | — | n/a |
| 5 | **State endpoint reports delivered work for happy path.** Given an authorize. When `GET /partner/state/{id}`. Then `known=true`, `responseDelivered=true`, one side effect with `delivered=true`. | state reflects delivered work | PASS — `PartnerHappyPathTest.stateEndpointReportsDeliveredWorkForHappyPath` | — | n/a |
| 6 | **State endpoint 404 for unknown id.** Given no request for an id. When `GET /partner/state/{id}`. Then 404. | 404 | PASS — `PartnerHappyPathTest.stateEndpointReturns404ForUnknownRequestId` | — | n/a |
| 7 | **Missing required field → 400.** Given an authorize with blank requestId. When posted. Then 400 (bean validation). | 400 | PASS — `PartnerHappyPathTest.missingRequiredFieldIsRejectedWith400` | — | n/a |
| 8 | **`latency` delays the response by the configured amount.** Given `LATENCY delayMillis=600` on AUTHORIZE. When authorize is called. Then it returns approved after ≥600ms. | delayed ≥600ms | PASS — `FaultInjectionTest.latencyDelaysResponseByConfiguredAmount` | — | n/a |
| 9 | **`fail-before-response` records side effect server-side but returns NO success (canonical drift trigger).** Given `FAIL_BEFORE_RESPONSE` on AUTHORIZE. When authorize is called. Then the caller gets HTTP 500, but `GET /state` shows `sideEffectsPerformed=true`, `responseDelivered=false`, one side effect with `delivered=false`. | 500 + undelivered side effect | PASS — `FaultInjectionTest.failBeforeResponseRecordsSideEffectButReturnsNoSuccess` | — | n/a |
| 10 | **`timeout` delays past the caller's bounded threshold.** Given `TIMEOUT delayMillis=1200` (> ~800ms). When authorize is called. Then it returns only after >800ms (the saga's bounded timeout would have fired). | delayed > threshold | PASS — `FaultInjectionTest.timeoutModeDelaysPastTheCallersBoundedThreshold` | — | n/a |
| 11 | **`decline` returns a declined response with reason.** Given `DECLINE prob=1.0 reason=INSUFFICIENT_FUNDS`. When authorize. Then `approved=false`, declineReason echoed. | declined | PASS — `FaultInjectionTest.declineModeReturnsADeclinedResponse` | — | n/a |
| 12 | **`error-rate` returns 500.** Given `ERROR_RATE prob=1.0`. When authorize. Then HTTP 500. | 500 | PASS — `FaultInjectionTest.errorRateModeReturns500` | — | n/a |
| 13 | **`duplicate` emits a duplicate marker, records work once, replays original.** Given `DUPLICATE` on AUTHORIZE. When authorize, then replay. Then first response `duplicate=true`; replay `duplicate=false` with same partnerRef (work recorded once). | duplicate marker + single record | PASS — `FaultInjectionTest.duplicateModeRespondsWithADuplicateMarker` | — | n/a |
| 14 | **`late-response` performs work then responds after delay.** Given `LATE_RESPONSE delayMillis=500`. When authorize. Then returns approved after ≥500ms and `state.responseDelivered=true`. | delayed normal response | PASS — `FaultInjectionTest.lateResponsePerformsWorkThenRespondsAfterDelay` | — | n/a |
| 15 | **`POST /control/faults` changes behavior at runtime; `reset` restores normal.** Given a healthy route, then ERROR_RATE applied at runtime, then reset. When authorize is called at each step. Then approve → 500 → approve, no restart. | runtime toggle works | PASS — `FaultInjectionTest.applyFaultChangesBehaviourAtRuntimeAndResetRestoresNormal` | — | n/a |
| 16 | **`error-rate` hits configured probability over N AND is deterministic when seeded (over HTTP).** Given `ERROR_RATE prob=0.4 seed=4242`, 200 requests, reset, replay same seed+ids. When both runs execute. Then the per-request error flags are identical across runs and the observed rate is within ±0.08 of 0.4. | reproducible + within tolerance | PASS — `FaultInjectionTest.errorRateHitsConfiguredProbabilityOverHttpAndIsDeterministicWhenSeeded` | — | n/a |
| 17 | **`duplicate` applies to capture and reverse routes too.** Given DUPLICATE on CAPTURE and REVERSE. When each is called. Then each response `duplicate=true`. | per-route duplicate | PASS — `FaultInjectionTest.duplicateModeAppliesToCaptureAndReverseRoutesToo` | — | n/a |
| 18 | **Faults are addressable per route.** Given DECLINE only on AUTHORIZE. When authorize declines but capture is called. Then authorize `approved=false`, capture `captured=true` (healthy). | per-route isolation | PASS — `FaultInjectionTest.faultsAreAddressablePerRoute` | — | n/a |
| 19 | **Control plane: GET reports active profile per route.** Given LATENCY applied to CAPTURE. When `GET /control/faults`. Then `$.CAPTURE.mode=LATENCY`, `$.AUTHORIZE.mode=NONE`. | active profiles reported | PASS — `ControlPlaneTest.getFaultsReportsActiveProfilePerRoute` | — | n/a |
| 20 | **Control plane: invalid profile → 400.** Given LATENCY with delayMillis=0 (violates `@AssertTrue`). When posted. Then 400. | 400 | PASS — `ControlPlaneTest.invalidProfileIsRejectedWith400` | — | n/a |
| 21 | **Control plane: `reset?state=true` clears recorded state.** Given a recorded request. When full reset. Then `GET /state` is 404. | state cleared | PASS — `ControlPlaneTest.resetWithStateFlagClearsRecordedRequestState` | — | n/a |
| 22 | **Control plane: plain reset keeps state, clears faults.** Given a recorded request. When plain reset. Then `GET /state` still 200. | state kept | PASS — `ControlPlaneTest.plainResetKeepsRecordedStateButClearsFaults` | — | n/a |
| 23 | **Control plane: malformed JSON → 400.** Given a non-JSON body. When posted to `/control/faults`. Then 400. | 400 | PASS — `ControlPlaneTest.malformedJsonBodyIsRejectedWith400` | — | n/a |
| 24 | **Determinism (unit): error-rate reproducible for a seed.** Given ERROR_RATE prob=0.3 seed=42, 2000 samples. When driven twice. Then identical outcome sequences. | identical | PASS — `FaultRegistryTest.errorRateIsReproducibleForAGivenSeed` | — | n/a |
| 25 | **Determinism (unit): error-rate / decline within tolerance; different seeds differ.** Given seeded ERROR_RATE/DECLINE over 2000. When driven. Then observed ≈ configured (±0.03); different seeds produce different sequences. | within tolerance | PASS — `FaultRegistryTest.errorRateHitsConfiguredProbabilityWithinTolerance`, `declineHits...`, `differentSeedsProduceDifferentSequences` | — | n/a |
| 26 | **Deterministic window fires exactly next-N then auto-heals.** Given ERROR_RATE `applyToNext=3`. When 5 requests. Then first 3 fire, then NONE (no reset call). | N then heal | PASS — `FaultRegistryTest.deterministicWindowFiresExactlyNextNThenHeals` | — | n/a |
| 27 | **Seeded jittered latency is reproducible and bounded.** Given LATENCY delay=100 jitter=50 seed=555. When two registries drive 20 requests. Then per-request delays match and fall in [100,150]. | reproducible jitter | PASS — `FaultRegistryTest.latencyDelayWithJitterIsReproducibleForAGivenSeed` | — | n/a |
| 28 | **No fault → normal; reset clears active profiles.** Given a fresh / reset registry. When `decide`. Then NONE. | NONE | PASS — `FaultRegistryTest.noFaultYieldsNormalDecision`, `resetClearsActiveProfiles` | — | n/a |
| 29 | **[ADDED] `fail-before-response` (deterministic next-1) records an UNDELIVERED side effect and 500s; no completion stored.** Given `FAIL_BEFORE_RESPONSE applyToNext=1`. When authorize. Then 500; `state` shows `responseDelivered=false`, one side effect `delivered=false`, and `response=null` (no replay record). | 500 + undelivered, no completion | PASS — `FailBeforeResponseSemanticsTest.failBeforeResponseRecordsUndeliveredSideEffectAndReturns500` | — | n/a |
| 30 | **[ADDED] Retry after `fail-before-response` RE-EXECUTES (records a 2nd side effect).** Given `FAIL_BEFORE_RESPONSE applyToNext=1`, first call 500s and records 1 side effect. When the SAME id is retried after auto-heal. Then it now 200s and a SECOND side effect is recorded (`delivered=false` then `delivered=true`) — the simulator does NOT dedup a half-done request. | re-executes, 2 side effects | PASS — `FailBeforeResponseSemanticsTest.retryingAfterFailBeforeResponseReExecutesAndRecordsASecondSideEffect` | — | n/a |
| 31 | **[ADDED] `timeout` actually completes work server-side (javadoc says "work is NOT performed").** Given `TIMEOUT delay=1200`. When authorize. Then it returns 200 after ≥1200ms AND `state` shows a delivered side effect — contradicting `FaultMode.TIMEOUT`'s javadoc. | side effect performed + delivered | PASS — `FailBeforeResponseSemanticsTest.timeoutModeActuallyCompletesTheWorkServerSideDespiteTheJavadocClaim` | — | n/a |

## Pass/fail summary

- **Total executed: 31 test cases** (34 underlying methods across `PartnerHappyPathTest`,
  `FaultInjectionTest`, `ControlPlaneTest`, `FaultRegistryTest`, `PartnerSimulatorApplicationTests`,
  plus 3 added in `FailBeforeResponseSemanticsTest`).
- **Pass: 31 / Fail: 0.**
- All HTTP cases ran over a real random-port server boundary; fault-registry determinism covered by
  pure unit tests.

## Prioritized issues (Spec 04)

- **Critical: none.** Confirmed: **no money/ledger logic** in the service (opaque amount/currency
  pass-through; no ledger dependency). The canonical drift trigger (`fail-before-response`) behaves
  exactly as the spec requires and is provable via the state endpoint.
- **Minor-1 (doc/behavior mismatch):** `FaultMode.TIMEOUT`'s javadoc says "The work is NOT performed
  … then a late response is produced for inspection," but the implementation completes the work
  normally after the delay (records a delivered side effect) — see case 31. This is harmless for the
  simulator (no money), and arguably the more useful behavior for the saga (a timeout + a late
  server-side success is itself a drift condition the saga must compensate). **Fix:** correct the
  javadoc to match, OR (if "held without doing work" was intended) split TIMEOUT from the normal
  completion path. Confirm the intended contract with Spec 03 before the fault harness leans on it.
- **Minor-2 (saga-contract note, not a sim defect):** `fail-before-response` deliberately stores no
  completion record, so a saga RETRY of the same request id re-executes and records a second side
  effect (case 30). This is correct for a drift trigger, but it means the simulator's idempotency
  guarantee covers only COMPLETED requests — exactly-once across a mid-flight failure is the SAGA's
  job (compensating reversal), not the simulator's. Ensure Spec 03's tests exercise this.
- **Minor-3 (test isolation):** `duplicate`/`late-response` "respond twice / arrive after the saga
  compensated" are represented via the `duplicate` flag + `late-response` delay, but there is no test
  that asserts a literal SECOND HTTP response is emitted for one request (the marker is on a single
  response). Acceptable for MVP since a real second wire response needs the saga to re-call; flag for
  Spec 07 if a true double-response on the wire is needed.
