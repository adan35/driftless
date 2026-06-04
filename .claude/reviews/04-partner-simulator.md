# Code Review - Spec 04: Partner Simulator

**Reviewer:** principal-engineer (final gate)
**Scope:** partner-simulator/ (standalone HTTP fault-injection boundary)
**Build:** full reactor green; QA 31 cases / 34 methods, all pass over a real random-port server.
**Verdict: GATE 04 - PASS.** Structurally clean, holds no money, faults are deterministic-when-seeded, controllers are thin, errors centralized. Two doc/contract findings (matching QA M4/M5) to settle with Spec 03 before the fault harness leans on them; neither blocks.

## What is SOUND

- No money/ledger logic - by construction. partner-simulator/pom.xml:21-45 depends on NO Driftless module (not even common); only spring-boot-starter-webmvc + validation + lombok. amountMinor/currency are opaque @NotBlank/@Min pass-through fields (AuthorizeRequest.java:19-25), never summed or interpreted. PartnerService javadoc and body confirm "no money or ledger semantics" (PartnerService.java:30-31). This is the cleanest possible satisfaction of "no money/ledger logic lives here."
- Real network boundary. Own main (PartnerSimulatorApplication), own port 8081 (application.properties:2), own boot jar via spring-boot-maven-plugin repackage (pom.xml:50-68). Reached over HTTP only; shares no in-JVM path with app. PartnerSimulatorApplicationTests boots it standalone on RANDOM_PORT.
- Fault injection is correct and deterministic-when-seeded. FaultRegistry.decide (FaultRegistry.java:95-115) resolves under a per-route lock so RNG advance + window countdown + profile read move atomically; apply() installs a fresh Random(seed) (RouteState.install, line 40-44) so a seeded request stream is fully reproducible (proven by FaultRegistryTest determinism cases and the over-HTTP seeded case 16). Deterministic next-N window auto-heals to NONE without a reset (line 103-110).
- fail-before-response is the canonical drift trigger, provable. PartnerService.execute FAIL_BEFORE_RESPONSE branch (PartnerService.java:117-129) records an undelivered SideEffect THEN throws InjectedFaultException -> 500, with NO completion record - so the state endpoint shows sideEffectsPerformed=true / responseDelivered=false (FaultInjectionTest case 9, FailBeforeResponseSemanticsTest case 29). Exactly what Spec 07 needs.
- Idempotent on request id for COMPLETED requests. execute() replays a stored record before applying any fault (PartnerService.java:101-106); duplicate/late-response record the work once and replay the original. Thin controllers (PartnerController, ControlController) validate + delegate; GlobalExceptionHandler maps InjectedFault->500, validation/malformed->400 centrally (GlobalExceptionHandler.java).
- Clock injected. SimulatorConfig.clock() (SimulatorConfig.java:15-18); no Instant.now()/new Date() in business logic. SLF4J {} placeholders throughout, no System.out.

## Findings

### Info

I1 - FaultMode.TIMEOUT javadoc contradicts behavior (doc/behavior mismatch; matches QA M4).
FaultMode.TIMEOUT javadoc (FaultMode.java:22-26) says "The work is NOT performed (the request is held, then a late response is produced for inspection)." But in PartnerService.execute, TIMEOUT falls into the default branch (PartnerService.java:138-144) which calls completeNormally - so the work IS recorded and a delivered side effect persists after the delay (proven by FailBeforeResponseSemanticsTest case 31). Harmless for the simulator (no money), and arguably the more useful drift condition (timeout + late server-side success the saga must compensate). Fix: correct the javadoc to match the implementation, OR if "held without doing work" was truly intended, split TIMEOUT off the normal completion path. Settle the intended contract with Spec 03 BEFORE the Spec 07 harness asserts on it.

I2 - fail-before-response stores no completion, so a saga retry re-executes (matches QA M5).
By design (PartnerService.java:117-129) FAIL_BEFORE_RESPONSE records the undelivered side effect but no completion record, so a retry of the same request id re-executes and records a SECOND side effect (FailBeforeResponseSemanticsTest case 30). Correct for a drift trigger - the simulator's idempotency covers only COMPLETED requests; exactly-once across a mid-flight partner failure is the SAGA's job (compensating reversal), not the simulator's. No change here; ensure Spec 03's tests exercise this retry-after-fail path.

I3 - Open /control/* plane is correct for a simulator. The control plane has no auth (anyone who can reach it can inject faults). This is correct and intended for a test/demo fault box that holds no money and is never the production issuer. Flagging only so it is never mistaken for a prod surface: in compose (Spec 09), keep the simulator on the internal network and do not expose /control/* publicly.

I4 - No literal second wire response for duplicate/late-response (matches QA M3). The "respond twice / arrive late" intent is modeled via a duplicate flag + delay on a single response, not a true second HTTP response. Acceptable for MVP (a real second wire response needs the saga to re-call). Flag for Spec 07 only if a genuine on-the-wire double-response is required.

## Verdict

GATE 04: PASS. A genuinely separate, money-free, deterministically-faulting HTTP boundary that exercises exactly the failure modes the saga claims to survive. Reconcile the TIMEOUT javadoc (I1) and confirm the fail-before-response retry contract (I2) with Spec 03 before the fault harness depends on them.
