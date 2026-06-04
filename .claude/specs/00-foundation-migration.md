# Spec 00 — Foundation & Migration

| Field | Value |
|-------|-------|
| **Owns** | Repo structure, build, module boundaries, conventions, ADRs, quality gates, `CLAUDE.md` |
| **CV story** | Spec-driven workflow |
| **Depends on** | — (root spec) |
| **Blocks** | Everything. This is a hard gate. |
| **Parallelizable** | No — must complete and be reviewed before any other agent starts |

## North star (shared mental model)

Driftless is an open-source issuer-processor core: an immutable double-entry ledger plus a
card-authorization engine that keeps money correct under failure. Signature property: **provable
zero drift**.

## The three invariants (apply to every module you scaffold)

1. **Balance invariant** — every journal write is balanced; the sum of all entries is always zero.
2. **Immutability** — entries are append-only; corrections are new compensating entries.
3. **Idempotency** — every mutating endpoint is safe to retry; a replay returns the original result.

## Goal

Convert the single-pom starter into a **multi-module Maven parent** that hosts a modular monolith
plus one separately-deployable service. Establish the conventions, quality gates, and the
`CLAUDE.md` that every downstream agent reads. **Freeze the cross-module contracts** (especially
the ledger's public interface, defined in Spec 01) so parallel agents cannot drift against a
moving API.

> Note: the working directory may currently be empty or contain only a starter pom. If empty,
> create the parent + modules from scratch following this spec. If a single-pom starter exists,
> migrate it (preserve any existing source by moving it into the appropriate module).

## Scope / Deliverables

1. **Maven parent (`pom.xml`)** with `<packaging>pom</packaging>`, shared dependency management
   (Spring Boot BOM, Postgres driver, Flyway, Micrometer/Prometheus, test libs), Java 21
   toolchain, and a `<modules>` list.

2. **Module layout** (modular monolith + one real service):

   ```
   driftless/
   ├── pom.xml                     # parent
   ├── CLAUDE.md                   # north star + invariants + conventions (this spec produces it)
   ├── docs/adr/                   # Architecture Decision Records
   ├── common/                     # shared value types: Money, Currency, ids, errors, clock
   ├── ledger/                     # Spec 01 — double-entry core (the foundation)
   ├── idempotency/                # Spec 02 — idempotency keys + transactional outbox
   ├── auth/                       # Spec 03 — auth/capture/reversal saga
   ├── rules/                      # Spec 05 — hot-path rule engine
   ├── tokens/                     # Spec 06 — tokenization lifecycle
   ├── recon/                      # Spec 07 — reconciliation + fault-injection + load gen
   ├── observability/              # Spec 08 — metrics wiring + dashboard provisioning
   ├── app/                        # Spring Boot bootstrap: wires modules, exposes the REST API
   └── partner-simulator/          # Spec 04 — SEPARATE deployable service (real network boundary)
   ```

   - `app` is the only module that depends on all internal feature modules and is the runnable
     monolith jar. `partner-simulator` is a **separate** Spring Boot app with its own `main` and
     its own container — it must not share a process or in-JVM call path with `app`.
   - Module dependency direction is strictly inward toward `ledger`/`common`. No cycles.
     `ledger` depends only on `common`. Feature modules depend on `ledger` + `common`, never on
     each other's internals (only on each other's published interfaces where unavoidable —
     prefer events via the outbox).

3. **`common` module** — the shared kernel:
   - `Money` (immutable; `long amountMinor` + `Currency`; arithmetic that rejects currency
     mismatch; no floating point).
   - Strongly-typed ids (`AccountId`, `TxId`, `EntryId`, `TokenId`, …) as value types.
   - A `Clock` abstraction (injectable; tests use a fixed clock — no `Instant.now()` calls
     scattered through business code).
   - Common error/result types and a base for domain exceptions.

4. **`CLAUDE.md`** at repo root containing: the north star paragraph, the three invariants, the
   module map, the ratified stack ADR, coding conventions, how to run/test, and the
   "non-negotiable gate" description. This file is the contract every agent reads.

5. **ADRs** in `docs/adr/` (use a lightweight MADR-style template):
   - ADR-0001: Modular monolith with one separate service (and why).
   - ADR-0002: Stack ratification (Java 21 / Spring Boot / Postgres / Flyway / Maven multi-module
     / Prometheus+Grafana / Docker Compose).
   - ADR-0003: Money as integer minor units.
   - ADR-0004: Append-only ledger + compensating entries (immutability).
   - ADR-0005: Frozen public ledger interface (links to Spec 01's interface section).

6. **Quality gates** (wired into the build, runnable locally and in CI):
   - Compile + unit tests on `mvn verify`.
   - Static analysis / linting (Spotless or Checkstyle for format; ErrorProne or SpotBugs
     optional but configured).
   - Test coverage reporting (JaCoCo) with a sane threshold on `ledger`.
   - A **placeholder for the property-based invariant test** wired into the build so Spec 07 can
     fill it in (a failing/`@Disabled` stub named `LedgerInvariantPropertyTest` is acceptable as
     a marker, but the Maven config that runs it must exist).
   - CI workflow (GitHub Actions) that runs `mvn verify` on push/PR.

7. **Architecture/contract freeze** — produce `docs/contracts/ledger-interface.md` (or a
   dedicated package-info + interface in the `ledger` module) that is the **frozen** ledger
   public API. Spec 01 authors the actual interface; Spec 00's job is to ensure it is published,
   versioned, and marked frozen before the parallel batch starts.

## Conventions to record in CLAUDE.md

- Package root: `io.driftless.<module>` (e.g. `io.driftless.ledger`).
- Public API of a module lives in an `api`/`spi` package; everything else is internal.
- No `Instant.now()` / `new Date()` in business logic — use the injected `Clock`.
- All money is `Money` (minor units). Raw `long`/`BigDecimal` amounts are not passed across
  module boundaries.
- Every mutating REST endpoint takes an `Idempotency-Key` header (contract defined in Spec 02).
- Migrations are Flyway, versioned, append-only (never edit a shipped migration).
- Tests: unit tests beside code; integration tests use Testcontainers (Postgres).

## Acceptance criteria (falsifiable)

- [ ] `mvn -q verify` succeeds from a clean checkout and builds every module.
- [ ] The parent pom declares all modules; module dependency graph has **no cycles** and
      `ledger` depends only on `common`.
- [ ] `partner-simulator` produces its **own** runnable boot jar, distinct from `app`.
- [ ] `CLAUDE.md` exists at root and contains the north star, the three invariants, the module
      map, and the stack ADR reference.
- [ ] ADR-0001 through ADR-0005 exist in `docs/adr/`.
- [ ] The frozen ledger interface contract document exists and is referenced from ADR-0005.
- [ ] `common.Money` rejects cross-currency arithmetic (unit-tested) and uses no floating point.
- [ ] CI workflow runs `mvn verify` and is green.
- [ ] The Maven config that will run the property-based invariant test exists (test may be a
      disabled stub at this stage).

## Gate

This spec **blocks all others**. Reviewer must confirm the ledger public interface is published
and marked frozen before the parallel batch (02/04/05/06) is generated. Do not start downstream
agents until this gate is green.

## Out of scope

- Any business logic inside feature modules (that belongs to their owning specs).
- The ledger's internal implementation (Spec 01 owns it; Spec 00 only guarantees its interface is
  published and frozen).
- Docker Compose / deploy (Spec 09) — but leave room for it; don't hard-code localhost assumptions
  that block containerization.
