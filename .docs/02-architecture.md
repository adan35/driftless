# Driftless — Architecture & Project Structure

This document explains how the codebase is organized, why it is organized that way, and how a
request flows through it.

## 1. Topology: a modular monolith + one real external service

Driftless is a **modular monolith** (`app`) — a single runnable Spring Boot process composed of
independently-compiled Maven modules — plus exactly **one separately-deployable service**, the
`partner-simulator`, reached only over HTTP.

Why this shape (see `docs/adr/ADR-0001`):

- **Transactional cohesion where it matters.** The ledger and the modules that move money through it
  share a database transaction when correctness demands it (e.g. "post a balanced entry *and* append
  its outbox event" must commit or roll back together). A microservices topology would turn that into
  a distributed transaction — exactly the seam where drift bugs hide.
- **A genuine failure boundary.** Drift bugs live *between* processes, not inside one transaction. So
  we keep one genuinely remote leg — the partner — that can be made to time out, fail, or stall, and
  prove the saga compensates correctly against it.
- **Microservices-ready.** Module boundaries are real (separate compilation units, no
  back-references), so any module could later become its own service without untangling a big ball
  of mud.

## 2. The module map

Dependencies point **strictly inward** toward `ledger` and `common`. Feature modules never depend on
each other's internals — they integrate through published `api`/`spi` packages and outbox events.

```
                         ┌──────────────────────────────────────────┐
                         │  app  (modular monolith: wires everything │
                         │  + REST + /actuator/prometheus)           │
                         └──────────────────────────────────────────┘
            ┌───────────┬───────────┬──────────┬───────────┬──────────────┐
            ▼           ▼           ▼          ▼           ▼              ▼
        ┌───────┐  ┌────────┐  ┌────────┐  ┌────────┐  ┌──────────┐  ┌──────────────┐
        │ auth  │  │ recon  │  │ tokens │  │ rules  │  │ observ.  │  │ idempotency  │
        │ (03)  │  │ (07)   │  │ (06)   │  │ (05)   │  │ (08)     │  │ + outbox(02) │
        └───┬───┘  └───┬────┘  └───┬────┘  └───┬────┘  └────┬─────┘  └──────┬───────┘
            │          │           │           │            │               │
            └──────────┴───────────┴─────┬─────┴────────────┴───────────────┘
                                         ▼
                                   ┌───────────┐
                                   │  ledger   │  Spec 01 — frozen api/spi
                                   │  (01)     │
                                   └─────┬─────┘
                                         ▼
                                   ┌───────────┐
                                   │  common   │  Money, typed ids, Clock, errors
                                   └───────────┘

   partner-simulator (04) ── a SEPARATE service/image. Not on any in-JVM call path.
                             The auth saga calls it over HTTP under a bounded timeout.
```

### Module responsibilities

| Module | Spec | Responsibility |
|--------|------|----------------|
| `common` | 00 | Kernel: `Money` (integer minor units), typed ids (`AccountId`/`TxId`/`EntryId`/`TokenId`), injected `Clock`, `DomainException`. |
| `ledger` | 01 | Immutable double-entry journal. **Frozen** `api` (`Ledger`) + `spi` (`HoldView`). Balance derived by summation; DB trigger blocks `UPDATE`/`DELETE` on `journal_entry`. |
| `idempotency` | 02 | `IdempotencyGuard` (record key in the same tx as the write) + transactional `OutboxWriter`/relay for exactly-once events. |
| `rules` | 05 | `RuleEngine.evaluate(AuthContext)` — limits/velocity/MCC on pre-compiled in-memory rules, **no I/O on the hot path** (p99 single-digit-ms). |
| `tokens` | 06 | Token lifecycle state machine (create/activate/suspend/resume/deactivate) with status propagation via the outbox. |
| `auth` | 03 | The authorization **saga**: authorize → capture → reverse, available-vs-posted via `HoldView`, **bounded-timeout compensating reversal**. |
| `recon` | 07 | Continuous balance-proof job, load generator, fault-injection harness, and the property-based invariant test (**the gate**). |
| `observability` | 08 | Micrometer metrics, Prometheus, provisioned Grafana "zero drift" dashboard. |
| `app` | 09 | The runnable monolith; wires modules, exposes REST + Prometheus endpoint, runs Flyway on startup. |
| `partner-simulator` | 04 | A genuinely separate service with a fault-injection control plane (latency/timeout/fail-before-response/duplicate/late-response). |

### Why the dependency direction is enforced

A module can only know about things *more fundamental* than itself. `ledger` knows `common` and
nothing else; it cannot accidentally couple to the auth saga or rules. This keeps the core
**stable** (the thing everyone depends on changes least) and makes the boundaries honest: if `auth`
wants something from `ledger`, it must go through the published `Ledger`/`HoldView` contract, which
is *frozen* and reviewed, rather than reaching into internals.

## 3. Package conventions inside a module

Each feature module is **packaged by feature** and split into visibility tiers:

```
io.driftless.<module>
├── api/      ← the published cross-module contract (records, interfaces, typed exceptions)
├── spi/      ← service-provider hooks others implement (e.g. ledger.spi.HoldView)
├── internal/ ← implementation: @Service business logic, JPA entities, repositories, mappers
├── config/   ← @ConfigurationProperties POJOs, @Configuration wiring
└── web/      ← thin @RestController + DTOs + @RestControllerAdvice (only where a module is web-facing)
```

Rules that keep this clean (from `CLAUDE.md`):

- **Thin controllers**: routing + (de)serialization only; business logic lives in `@Service`.
  Controllers never return JPA entities — they map to DTOs/records.
- **Constructor injection** via `final` fields (`@RequiredArgsConstructor`); no field `@Autowired`.
- **Config as POJOs**: `@ConfigurationProperties` over scattered `@Value`.
- **Money is `Money`**: never a raw `long`/`BigDecimal` across a module boundary, never a float.
- **Time is an injected `Clock`**: never `Instant.now()`/`new Date()` in business logic, so tests can
  freeze time.
- **Errors are typed**: `DomainException` subclasses, mapped centrally by `@RestControllerAdvice`.

## 4. Persistence & migrations

- **PostgreSQL** with **Flyway** migrations. Each module owns its own migration location
  (`ledger` → `db/migration`, `idempotency` → `db/migration_idempotency`,
  `tokens` → `db/migration_tokens`, …) so modules evolve their schema independently and the `app`
  configures all locations.
- Migrations are **append-only**: never edit a shipped migration; add a new versioned one.
- **Balance is never stored.** There is deliberately no `balance` column. Posted balance is always
  `SELECT SUM(...)` over `journal_entry`, signed by direction. This is what makes drift *impossible
  to hide* — the books are their own source of truth.
- Integration tests use **Testcontainers Postgres** (a real database, not H2/mocks), so schema,
  triggers, and constraints are exercised exactly as in production.

## 5. The lifecycle of an authorization (request flow)

```
POST /authorizations  { account, amount, mcc, merchantId, tokenId? }   (Idempotency-Key header)
   │
   ├─▶ idempotency guard opens (same key replays the original result, no double-effect)
   │
   ├─▶ assemble AuthContext (available balance from ledger, velocity snapshot)
   │
   ├─▶ RuleEngine.evaluate  ──DECLINE──▶ DECLINED  (no hold, no money moved)
   │        │ APPROVE
   │        ▼
   ├─▶ place HOLD via ledger  (available -= amount; posted unchanged; HoldView now reports it)
   │        │
   │        ▼
   ├─▶ call partner.authorize OVER HTTP, under a HARD bounded timeout
   │        │ approve            │ timeout / 5xx / no-response
   │        ▼                    ▼
   │   AUTHORIZED         COMPENSATING REVERSAL ─ release hold ─▶ REVERSED   (zero drift)
   │        │
   │        ├── capture ─▶ post settlement (posted moves, hold released) ─▶ CAPTURED
   │        └── reverse ─▶ compensating reversal (release hold) ─▶ REVERSED
   │
   └─▶ every step: a balanced ledger post + an outbox event, committed in ONE transaction
```

The crux is the **bounded-timeout compensating reversal**: if the partner leg stalls, the saga does
not leave the hold dangling — it compensates, and because both the partner endpoint and the saga are
idempotent, a *late* partner success arriving after compensation is absorbed without creating drift.

## 6. Quality gates (CI)

- **Spotless** (palantir-java-format) — formatting gate.
- **JaCoCo** — per-module line/branch-coverage minimums where logic exists.
- **Property-based invariant test** (`recon` / jqwik) — the non-negotiable gate: for random
  **sequential and concurrent, multi-currency** sequences of `auth/capture/reversal/fault`,
  `∑ journal entries == 0` per currency, no double-effect, no dangling hold.
- **Testcontainers** integration tests — real Postgres, real triggers/constraints.
- **ArchUnit** — fails the build if any module reaches into another module's `internal`/`web`
  (modules integrate only through `api`/`spi` + `common`), or if a package cycle appears. The one
  intentional, documented exception is the in-process saga load/demo harness in `recon`.
- **maven-enforcer** — locks Java 21 / Maven floor, bans duplicate dependency versions, requires
  reactor module convergence and pinned plugin versions.
- **CycloneDX SBOM** + CI **Trivy** image scan + **Dependabot** / dependency-review — supply-chain
  provenance and vulnerability surfacing.

Run locally: `./mvnw verify` (needs a running Docker daemon for Testcontainers).

## 7. Public REST surface

The modular monolith (`app`) exposes a documented, idempotent REST API (browsable as OpenAPI at
`/swagger-ui` / `/openapi.yaml`):

| Area | Endpoints |
|------|-----------|
| Accounts | `POST /accounts` (open), `POST /accounts/{id}/funding` (balanced load), `GET /accounts/{id}`, `GET /accounts/{id}/balance` (posted + available) |
| Statements | `GET /accounts/{id}/statement?after=&limit=` — **keyset/seek** pagination over the immutable journal (stable under appends, no `OFFSET`) |
| Authorizations | `POST /authorizations`, `POST /authorizations/{id}/capture`, `POST /authorizations/{id}/reverse`, `GET /authorizations/{id}` |
| Tokens | `POST /tokens` + `activate`/`suspend`/`resume`/`deactivate`, `GET /tokens/{id}` |
| Reconciliation | `POST /reconciliation/run`, `GET /reconciliation/latest`, `GET /reconciliation/{id}[/rca]` |
| Ops | `/actuator/health`, `/actuator/prometheus` |

Every mutating endpoint takes an `Idempotency-Key` (missing ⇒ 400, same key + different body ⇒ 409,
replay ⇒ original 2xx). Errors are RFC-7807 `ProblemDetail` with a stable machine-readable `code`
(e.g. `BALANCE_INVARIANT_VIOLATION`, `IDEMPOTENCY_CONFLICT`, `ACCOUNT_NOT_FOUND`,
`CURRENCY_MISMATCH`). Money is always integer minor units.
