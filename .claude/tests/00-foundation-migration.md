# Test Cases — Spec 00: Foundation & Migration

QA gate verification for the multi-module migration. Every row was **executed**; `actual` and
`reason_of_failure` are filled from real command output (not from the developer's report).

- Environment: Windows 11, Java 21.0.2 (Temurin/Oracle JDK-21), Maven wrapper `./mvnw.cmd`,
  Spring Boot 4.0.6, jqwik 1.9.1, JUnit Jupiter 6.0.3.
- Command of record: `./mvnw.cmd -B verify` (also ran `./mvnw.cmd -q clean verify` for a from-clean
  pass; both green). `-q` was dropped for the recorded run because it suppresses the reactor summary
  and per-test surefire counts that this gate must capture.
- `db_config` is **n/a (no DB at Spec 00)** for every row — no Postgres/Testcontainers is exercised
  at the foundation stage.

## Verbatim build result

```
[INFO] Reactor Summary for driftless-parent 0.0.1-SNAPSHOT:
[INFO] driftless-parent ................................... SUCCESS [  2.959 s]
[INFO] common ............................................. SUCCESS [ 10.796 s]
[INFO] ledger ............................................. SUCCESS [  0.432 s]
[INFO] idempotency ........................................ SUCCESS [  0.281 s]
[INFO] auth ............................................... SUCCESS [  0.491 s]
[INFO] rules .............................................. SUCCESS [  0.272 s]
[INFO] tokens ............................................. SUCCESS [  0.289 s]
[INFO] recon .............................................. SUCCESS [  4.636 s]
[INFO] observability ...................................... SUCCESS [  0.240 s]
[INFO] app ................................................ SUCCESS [ 19.877 s]
[INFO] partner-simulator .................................. SUCCESS [ 17.915 s]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  59.681 s
```

Aggregate tests across the reactor: **25 run, 0 failures, 0 errors, 1 skipped** (the skipped one is
the intentional `recon` property stub). `clean verify` (from-clean) also returned `EXIT_CODE=0`.

## Test cases

| sr_no | test case (Given/When/Then) | expected | actual | reason_of_failure | db_config |
|-------|-----------------------------|----------|--------|-------------------|-----------|
| 01 | **Given** a clean checkout, **When** I run `./mvnw.cmd -q clean verify` from the repo root, **Then** the build completes successfully. | BUILD SUCCESS, exit 0. | PASS — `EXIT_CODE=0` on the from-clean `-q clean verify`; the recorded `-B verify` printed `BUILD SUCCESS`, Total time 59.681 s. | — | n/a (no DB at Spec 00) |
| 02 | **Given** the parent pom, **When** I inspect `<modules>` and run the reactor, **Then** all 10 modules (common, ledger, idempotency, auth, rules, tokens, recon, observability, app, partner-simulator) build. | 11 reactor entries (parent + 10 modules) all SUCCESS. | PASS — reactor shows `[1/11]`…`[11/11]`, every line SUCCESS. All 10 modules present in `pom.xml` `<modules>`. | — | n/a (no DB at Spec 00) |
| 03 | **Given** `ledger/pom.xml`, **When** I read its dependencies, **Then** `ledger` depends ONLY on `common`. | Single dependency: `io.driftless:common`. | PASS — `ledger/pom.xml` declares exactly one dependency, `common`. No Spring/JPA/SQL deps. | — | n/a (no DB at Spec 00) |
| 04 | **Given** the feature module poms, **When** I read idempotency/auth/rules/tokens/observability deps, **Then** none depends on another feature module's internals. | Each depends on `common` (+`ledger` where applicable) only; no feature→feature edge. | PASS — idempotency/auth/rules/tokens → {common, ledger}; observability → {common}. No cross-feature dependency; no cycles. | — | n/a (no DB at Spec 00) |
| 05 | **Given** `partner-simulator/pom.xml`, **When** I read its deps, **Then** it depends on NO Driftless module (a real HTTP-only boundary). | Only Spring Web (+ test); no `io.driftless:*`. | PASS — deps are `spring-boot-starter-webmvc` (+ `-test`) only; not even `common`. Confirms separate-service boundary. | — | n/a (no DB at Spec 00) |
| 06 | **Given** the build output, **When** verify finishes, **Then** `app` produces its own runnable Spring Boot jar with `@SpringBootApplication` main and a passing context-load test. | `app/target/app-*.jar` repackaged; `Start-Class=io.driftless.app.DriftlessApplication`; `DriftlessApplicationTests.contextLoads` passes. | PASS — `app/target/app-0.0.1-SNAPSHOT.jar` (19,847,358 B) has `BOOT-INF/` + `Start-Class: io.driftless.app.DriftlessApplication`; `DriftlessApplicationTests` Tests run: 1, Failures: 0. | — | n/a (no DB at Spec 00) |
| 07 | **Given** the build output, **When** verify finishes, **Then** `partner-simulator` produces its own runnable jar with a DISTINCT main and a passing context-load test. | `partner-simulator/target/*.jar` repackaged; `Start-Class=io.driftless.partnersim.PartnerSimulatorApplication`; context-load test passes. | PASS — `partner-simulator/target/partner-simulator-0.0.1-SNAPSHOT.jar` (19,809,134 B) has `BOOT-INF/` + `Start-Class: io.driftless.partnersim.PartnerSimulatorApplication`; `PartnerSimulatorApplicationTests` Tests run: 1, Failures: 0. Two distinct boot jars confirmed. | — | n/a (no DB at Spec 00) |
| 08 | **Given** `common.Money`, **When** `plus`/`minus`/`compareTo` are called with two different currencies, **Then** a typed `CurrencyMismatchException` is thrown. | Tests `plusRejectsCrossCurrency`, `minusRejectsCrossCurrency`, `compareRejectsCrossCurrency` PASS. | PASS — MoneyTest XML report: `tests=18, failures=0`; the three cross-currency cases each present with real exec time. `CurrencyMismatchException extends DomainException`. | — | n/a (no DB at Spec 00) |
| 09 | **Given** `common` main sources, **When** I grep for `double`/`float`/`Double`/`Float`/`BigDecimal`, **Then** none touches money math. | Zero matches in `common/src/main`. | PASS — `No matches found` in `common/src/main`. Money is `long amountMinor` with `Math.addExact/subtractExact/negateExact` (integer-only, overflow-checked). | — | n/a (no DB at Spec 00) |
| 10 | **Given** the ledger contract, **When** I grep for an implementation (`*LedgerImpl`, JPA `@Entity`/`@Repository`, `JdbcTemplate`, `INSERT`/`UPDATE`/`DELETE`, `implements Ledger`), **Then** none exists — contract shapes only. | No implementation/SQL/JPA in `ledger`. | PASS — `No matches found`. `ledger` contains only `api/` and `spi/` packages (records, enums, interface, exception, SPI). | — | n/a (no DB at Spec 00) |
| 11 | **Given** Spec 01's FREEZE list, **When** I compare it to `io.driftless.ledger.api`, **Then** the published shapes match exactly. | Direction, AccountType, Account, PostingLine, PostingRequest, PostingResult, Balance, JournalEntry, Transaction, Page, Ledger (5 methods), BalanceInvariantViolation, + spi.HoldView. | PASS — all 12 api types present with matching record components/enum constants; `Ledger` has exactly `openAccount`, `post`, `balanceOf`, `findTransaction`, `entriesFor`; `HoldView.activeHoldTotal(AccountId, Currency)` present. Matches `docs/contracts/ledger-interface.md`. | — | n/a (no DB at Spec 00) |
| 12 | **Given** the frozen packages, **When** I read `api/package-info.java` and `spi/package-info.java`, **Then** they are marked FROZEN and point at the contract doc + ADR-0005. | Both package-infos say FROZEN and reference the contract. | PASS — both package-info files state "FROZEN … as of the Spec 00/01 gate", reference `docs/contracts/ledger-interface.md` and ADR-0005. | — | n/a (no DB at Spec 00) |
| 13 | **Given** the ledger contract by shape, **When** I inspect the `Ledger` interface, **Then** it exposes NO mutate/delete method (append-only substrate of invariant 2). | Only open/post/read methods; no update/delete/edit. | PASS — interface has no `update`/`delete`/`edit`/`void set...`; corrections are new `post` calls per javadoc. Append-only-by-shape confirmed. | — | n/a (no DB at Spec 00) |
| 14 | **Given** the recon module, **When** verify runs the surefire/jqwik runner over `LedgerInvariantPropertyTest`, **Then** the stub is genuinely **Skipped** (NOT executed, NOT passed). | recon surefire: Tests run 1, Skipped 1; `<skipped .../>` in XML. | PASS — `Tests run: 1, Failures: 0, Errors: 0, Skipped: 1`; XML `<testcase name="sumOfAllJournalEntriesIsZero"><skipped message="Spec 07 implements the zero-drift property; Spec 00 only wires the harness"/></testcase>`. Maven logs it as `[WARNING]`, i.e. skipped, not a pass. | — | n/a (no DB at Spec 00) |
| 15 | **Given** the jqwik `@Disabled` subtlety, **When** I check the import in `LedgerInvariantPropertyTest`, **Then** it uses `net.jqwik.api.Disabled` (NOT JUnit's), so jqwik actually skips it. | Import is `net.jqwik.api.Disabled`. | PASS — line 3 imports `net.jqwik.api.Disabled`; `@Property @Disabled(...)`. Confirmed by the actual Skipped result in row 14 (a JUnit `@Disabled` would have let the property RUN and pass vacuously). | — | n/a (no DB at Spec 00) |
| 16 | **Given** the property harness wiring, **When** I read `recon/pom.xml` and the surefire classpath, **Then** jqwik is on the test classpath and discovered. | jqwik test dependency present; engine on classpath. | PASS — `recon/pom.xml` has `net.jqwik:jqwik` (test); surefire XML classpath includes `jqwik-1.9.1`, `jqwik-engine-1.9.1`. Stub discovered (it appears in the report). | — | n/a (no DB at Spec 00) |
| 17 | **Given** the repo root, **When** I open `CLAUDE.md`, **Then** it has north star + the three invariants + module map + stack ADR reference. | All four present. | PASS — `CLAUDE.md` contains north star, the three invariants, the module map, the stack table, and references the specs/ADRs. | — | n/a (no DB at Spec 00) |
| 18 | **Given** `docs/adr/`, **When** I list it, **Then** ADR-0001..0005 all exist. | Five ADR files present. | PASS — ADR-0001-modular-monolith, ADR-0002-stack-ratification, ADR-0003-money-minor-units, ADR-0004-append-only-ledger, ADR-0005-frozen-ledger-interface all present. | — | n/a (no DB at Spec 00) |
| 19 | **Given** ADR-0005, **When** I open it, **Then** it links `docs/contracts/ledger-interface.md`. | Link present; contract doc exists. | PASS — ADR-0005 links `[../contracts/ledger-interface.md]`; the contract doc exists and enumerates the frozen surface (enums, 8 records, the 5-method interface, the exception, the SPI). | — | n/a (no DB at Spec 00) |
| 20 | **Given** `.github/workflows/ci.yml`, **When** I read it, **Then** it sets up JDK 21 and runs `mvn verify` on push and pull_request. | JDK 21 + `verify` on push & PR. | PASS — workflow `on: push (branches **) , pull_request`; `setup-java` temurin 21; step runs `./mvnw -q -B verify`. | — | n/a (no DB at Spec 00) |
| 21 | **Given** the `common` coverage gate, **When** verify runs JaCoCo on `common`, **Then** the 0.80 line floor holds with Money fully exercised. | `common` JaCoCo check passes. | PASS — `common` SUCCESS with `jacoco-check` (BUNDLE LINE >= 0.80) bound to verify; consistent with MoneyTest's 18 passing cases. | — | n/a (no DB at Spec 00) |
| 22 | **Given** the injected-Clock convention, **When** I inspect `common`, **Then** a `Clock` bean is published and no `Instant.now()`/`new Date()` lives in business logic. | `ClockConfig` provides `Clock`; no scattered now()/Date in business code. | PASS — `ClockConfig` `@Bean Clock clock() = Clock.systemUTC()`; no `Instant.now()`/`new Date()` in `common`/`ledger` business code (the contract carries `Instant occurredAt` supplied by the caller's clock). | — | n/a (no DB at Spec 00) |

## Pass / fail summary

- **22 / 22 cases PASS. 0 fail.**
- Reactor: **BUILD SUCCESS**, 11/11 modules SUCCESS, total 59.681 s.
- Tests: **25 run, 0 failures, 0 errors, 1 intentionally skipped** (the `recon` zero-drift property
  stub, correctly skipped via `net.jqwik.api.Disabled`).
- All 9 spec acceptance criteria independently verified against real output.

## Invariant-substrate verification (foundation stage)

No money moves yet, so runtime ledger invariants are not exercisable; the **substrate** of each was
verified explicitly:

- **Balance / Immutability substrate** — `Money` is integer minor units with overflow-checked
  integer arithmetic and NO float/double/BigDecimal (rows 08, 09). The frozen `Ledger` contract
  exposes no mutate/delete method, so immutability is enforced by shape (row 13).
- **Zero-drift gate wiring** — `LedgerInvariantPropertyTest` is present in `recon`, jqwik is on the
  test classpath, and it is genuinely **Skipped** (rows 14–16) — NOT silently executing/passing.
  This is the subtlety the developer flagged and it is handled correctly.
- **Frozen contract fidelity** — published `io.driftless.ledger.api`/`spi` exactly matches Spec 01's
  freeze list, with no implementation, marked FROZEN in package-info (rows 10–12).

## Prioritized issue list

- **Critical:** none. No drift risk introduced (no money path exists yet); the zero-drift gate is
  wired and correctly skipped; the frozen contract is published exactly and downstream agents can
  safely build against it.
- **Major:** none.
- **Minor / observations (non-blocking — do NOT reopen the gate for these):**
  1. **Cosmetic surefire `.txt` undercount for `MoneyTest`.** Because every `@Test` lives in a
     `@Nested` class, the per-class console summary file
     `common/target/surefire-reports/io.driftless.common.money.MoneyTest.txt` prints
     `Tests run: 0`, while the authoritative XML and the reactor breakdown show all **18** tests ran
     and passed (construction 4, arithmetic 9, comparison 2, sign predicates 3). No action required;
     flagged only so a future reader does not misread the `.txt` line as "Money is untested".
  2. **`Money` does not implement `Comparable<Money>`.** It has a `compareTo(Money)` method (correct,
     currency-checked) but the class is not declared `implements Comparable<Money>`, so it cannot be
     used directly with `Collections.sort`/`TreeMap`. Spec does not require it; note for Spec 01 if
     ordered collections of `Money` are later needed.
  3. **CI runs the Maven wrapper (`./mvnw -q -B verify`), not a bare `mvn`.** This satisfies the
     criterion and is arguably better (pinned Maven). Only noting because the criterion text says
     "runs `mvn verify`". CI greenness itself is not verifiable locally (no Actions run here); the
     equivalent command was run locally and is green.
  4. **CI `-q` would hide the property-stub Skipped status in Actions logs.** Same trap I hit:
     `-q` suppresses the surefire per-test summary. Consider dropping `-q` in CI (keep `-B`) so the
     "Skipped: 1" line for `LedgerInvariantPropertyTest` is visible in CI logs as evidence the gate
     is wired-but-inert (and, once Spec 07 lands, that it actually runs). Cosmetic, not a gate fail.
