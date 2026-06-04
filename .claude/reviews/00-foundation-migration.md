# Review 00 - Foundation and Migration (Spec 00)

- Reviewer: principal-engineer (final gate).
- Scope: multi-module reactor scaffold + common kernel + FROZEN io.driftless.ledger.api/spi contract + ADR-0001..0005 + docs/contracts/ledger-interface.md + CI + quality gates.
- Mode: read-only. No source modified.
- Independent build evidence: QA clean verify = BUILD SUCCESS, 11 reactor entries, 25 tests / 1 skipped (the disabled property stub). Re-judged by reading the actual tree, not just re-running.

## Verdict summary

The contract matches Spec 01 FREEZE list EXACTLY (12 api types + spi.HoldView), with no implementation, JPA, SQL, or business logic leaking into ledger. The freeze is properly MARKED (both package-info.java files, the contract doc, ADR-0005). The invariant substrate is sound: Money is integer minor units with overflow-checked arithmetic and zero float/double/BigDecimal; cross-currency ops throw a typed CurrencyMismatchException; the Ledger interface exposes no update/delete (append-only by shape); the idempotency key + replayed flag are carried on PostingRequest/PostingResult. Dependency direction is strictly inward; no cycles; ledger depends only on common; partner-simulator shares no in-JVM path with app. The zero-drift property stub is wired with jqwik own @Disabled so it is genuinely Skipped (not vacuously passed) and will run once Spec 07 removes the annotation.

No Critical findings. No freeze defects. No invariant breaches. All items below are Warning (non-blocking) or Info.

---

## Critical

None.

---

## Warning

### W1 - Money.amountMinor Javadoc permits negatives, contradicting the per-line strictly-positive amount invariant
- common/src/main/java/io/driftless/common/money/Money.java:15 documents amountMinor as "signed ... may be negative (e.g. a reversal)".
- Issue: Money is the type carried by PostingLine.amount (ledger/.../api/PostingLine.java:10) and JournalEntry.amount (.../api/JournalEntry.java:15-16). Both Spec 01 and CLAUDE.md require a posting-line amount to be STRICTLY POSITIVE with Direction carrying the sign. The kernel deliberately allows a signed Money (correct for balances, deltas, and negate()-based compensations). The latent risk is that nine parallel agents read this Money Javadoc as license to pass a negative amount into a PostingLine, which Spec 01 post() must reject. The contract carrier itself enforces nothing about positivity.
- Why not Critical: Money is the right place to allow signed values (deltas/reversals); the positivity rule legitimately belongs to post() validation in Spec 01, which does not exist yet. No money path exists, so nothing is wrong at runtime today.
- Concrete fix (doc-only, no freeze impact): in PostingLine (PostingLine.java:6-10) and JournalEntry (JournalEntry.java:8-14) Javadoc, state explicitly that amount MUST be strictly positive and that post() rejects non-positive amounts, so the contract reading is unambiguous before parallel work starts. Optionally have Spec 01 add a compact-constructor guard (amount.isPositive()) on PostingLine. Leave Money itself signed.

### W2 - recon inherits the jqwik dependency with no version of its own; the gate depends entirely on parent dependencyManagement staying intact
- recon/pom.xml:36-40 declares net.jqwik:jqwik (scope test) with NO version element; the version resolves only from pom.xml:96-101 (dependencyManagement, jqwik.version = 1.9.1).
- Issue: this is correct Maven and works today (QA saw jqwik-1.9.1 + jqwik-engine-1.9.1 on the recon test classpath and the stub Skipped). The Warning is about the non-negotiable gate durability: if a future edit removes or reshapes the managed jqwik entry, recon loses its version with only a build-time resolution error - and a misconfigured engine is exactly how a property gate goes hollow. There is no test asserting the engine is actually present.
- Concrete fix: add a comment on recon/pom.xml:36 noting the version is intentionally managed by the parent; and have Spec 07 add a trivial always-on jqwik sanity property (a non-@Disabled @Property that asserts something cheap) alongside the disabled invariant stub, so verify fails loudly if the jqwik engine ever drops off the classpath rather than only skipping.

### W3 - CI runs with -q, which suppresses the surefire per-test summary that proves the gate is wired-but-inert
- .github/workflows/ci.yml:24 runs ./mvnw -q -B verify.
- Issue: -q hides the reactor per-test breakdown and the Skipped:1 line for LedgerInvariantPropertyTest in Actions logs. The whole point of the wired-but-disabled stub is that a reviewer can SEE it is skipped (and, post-Spec-07, see it RUN). Under -q the CI log is silent on this, weakening the gate auditability. QA flagged the same trap.
- Concrete fix: drop -q, keep -B: run ./mvnw -B verify. Non-blocking but recommended before Spec 07 so the gate status is visible in CI from the start.

---

## Info

### I1 - Money has a currency-checked compareTo(Money) but does not implement Comparable<Money>
- Money.java:91-94. The method is correct (currency-checked, Long.compare) but the class is not declared implements Comparable<Money>, so Money cannot be used with Collections.sort / TreeMap directly. Spec 00 does not require it. Developer self-flagged (deviation c). Recommend Spec 01/03 add implements Comparable<Money> if ordered collections of amounts are later needed (purely additive; no freeze impact). Note: implementing Comparable means compareTo throws on cross-currency, a documented contract-of-compareTo wrinkle - acceptable and already how it behaves.

### I2 - common carries a Spring @Configuration (ClockConfig) in the otherwise-plain kernel
- ClockConfig.java:15-22; dependency at common/pom.xml:22-26 (spring-context, scope provided). Judged ACCEPTABLE (developer deviation b). provided scope keeps common a plain jar for any non-Spring consumer (ledger pulls in no Spring transitively - confirmed ledger/pom.xml has only common), while Spring modules put spring-context on their own runtime classpath. Double-bean risk is low: a single @Bean Clock that tests/environments are documented to override via @TestConfiguration. Two non-blocking watch-items for downstream: (a) if more than one module component-scans io.driftless and also defines a Clock, prefer @ConditionalOnMissingBean when Spec 02+ wires real config, to avoid a duplicate-bean clash; (b) a non-Spring consumer gets the Clock convention but not the bean - the intended trade-off. Fine as is.

### I3 - jqwik @Disabled is the correct annotation (developer self-flag a is sound)
- recon/src/test/java/io/driftless/recon/LedgerInvariantPropertyTest.java:3,20 imports and uses net.jqwik.api.Disabled - NOT JUnit @Disabled (which jqwik engine ignores, letting the property run and pass vacuously). This is the subtle, correct choice; QA confirmed Skipped:1. The placeholder body returns operationCount >= 0 and will be replaced by Spec 07 with the real SUM(journal entries)==0 property. No action.

### I4 - Reconciliation global-sum read-path is not on the frozen surface (additive, non-breaking)
- ledger/.../api/Ledger.java:39 exposes only entriesFor(AccountId, Page) - a PER-ACCOUNT read that presupposes the caller already enumerates every AccountId. There is no frozen method to list all accounts or stream all journal entries globally. This does NOT block the freeze: Spec 07 (.claude/specs/07-reconciliation-reliability.md:34-36) proves global zero by summation over journal_entry - i.e. the recon job reads the table directly, and any future convenience method on Ledger would be ADDITIVE (new methods do not break existing consumers or reopen the freeze). Flagged only so the PM knows recon will summate at the table/repository level, not through the per-account API.

### I5 - findTransaction / balanceOf return concrete types, not Optional - and that is correct here
- Ledger.java:33,36. CLAUDE.md prefers Optional over null, but Spec 01 FREEZE list specifies Transaction findTransaction(TxId id) and Balance balanceOf(AccountId) as bare return types. The contract matches the frozen spec exactly, which is the higher obligation at this gate. Spec 01 should define not-found semantics (throw a typed DomainException vs Optional) in its implementation; switching to Optional<Transaction> later is a freeze-surface change and must go through the ADR-0005 reopen process. No change now.

### I6 - partner-simulator hardcodes server.port=8081; does not block containerization
- partner-simulator/src/main/resources/application.properties:2. A fixed port (not a hardcoded host) is fine - Spec 09 maps/overrides ports via Compose/env. No localhost/host assumptions found anywhere (grep clean). Noted only against the Spec 00 do-not-hard-code-localhost guidance; an env-overridable form like the property defaulting to 8081 would be marginally cleaner for Spec 09.

---

## Invariant and boundary checks (explicitly verified by reading the tree)

- Balance substrate: Money integer minor units; Math.addExact/subtractExact/negateExact (overflow throws); zero float/double/BigDecimal in any src/main (grep: only the words double-entry in prose). BalanceInvariantViolation extends DomainException present (.../api/BalanceInvariantViolation.java).
- Immutability substrate: Ledger interface has no update/delete/edit/setter (.../api/Ledger.java); all api types are records (immutable). ADR-0004 ratifies append-only + compensating entries.
- Idempotency substrate: PostingRequest.idempotencyKey (PostingRequest.java:13), PostingResult.replayed (PostingResult.java:11) and Transaction.idempotencyKey present so Spec 02/03 can honor replay through the frozen contract.
- Freeze marking: api/package-info.java and spi/package-info.java both state FROZEN and cite the contract doc + ADR-0005; docs/contracts/ledger-interface.md enumerates the full surface; ADR-0005 links the contract doc.
- Dependency direction: ledger -> {common} only. idempotency/auth/rules/tokens -> {common, ledger}. observability -> {common}. recon -> {common, ledger}. app -> all 8 features (NOT partner-simulator). partner-simulator -> only Spring Web (no io.driftless:* at all). No feature-to-feature edge; no cycle. Two distinct boot jars (only app and partner-simulator run repackage; app excludes lombok).
- Conventions: no @Autowired (grep clean), no System.out/System.err, no Instant.now()/new Date() outside the ClockConfig Javadoc that forbids them; typed ids reject null; package-by-feature under io.driftless.<module>; data holders are records; DomainException is unchecked base.
- Hygiene: no secrets/passwords/keys/PAN (grep clean); target/ untracked (gitignored); the original starter io.driftless.DriftlessApplication was migrated into app as io.driftless.app.DriftlessApplication (git shows old paths deleted, new module dirs added) - matches the spec preserve-by-moving instruction.

## Quality-gate integrity

- Spotless (palantir-java-format) bound to verify repo-wide via the parent build/plugins block - formatting breakage fails the build. Real, not hollow.
- JaCoCo coverage CHECK enforced where logic exists: common/pom.xml:47-73 BUNDLE LINE >= 0.80, bound to verify. Honestly deferred (not silently disabled) on ledger with an in-pom rationale (ledger/pom.xml:28-32): interfaces/records have no coverable logic, so a floor there would fail the foundation gate; Spec 01 adds the implementation and raises the floor. Documented in ADR-0005.
- Property gate genuinely wired and genuinely inert (jqwik @Disabled, engine on classpath, discovered, Skipped:1) - it will RUN when Spec 07 removes the annotation. See I3/W2.

---

## GATE: PASS

The ledger public interface is published, matches Spec 01 FREEZE list exactly, contains no implementation, and is properly marked frozen (package-info x2 + contract doc + ADR-0005). The three invariants substrates are correct; module boundaries and dependency direction are clean; the non-negotiable zero-drift gate is wired and correctly skipped. No Critical findings and no freeze defects. The PM MAY mark io.driftless.ledger.api/spi FROZEN and release the 02/04/05/06 parallel batch.

Recommended (non-blocking, address early in the batch - none reopens the freeze): W1 (clarify strictly-positive amount in PostingLine/JournalEntry Javadoc), W2 (jqwik version comment + an always-on sanity property in Spec 07), W3 (drop -q in CI so the gate Skipped/Run status is visible).
