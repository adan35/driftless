# CLAUDE.md — Driftless project context

> Single source of context for every AI role (developer, qa-engineer, code-reviewer,
> spec-architect) and the `/deliver` PM skill. Read this first, then your owning spec under
> `.claude/specs/`. The spec index is `.claude/specs/README.md`.

## What Driftless is

Driftless is an open-source **issuer-processor core**: an immutable double-entry ledger plus a
card-authorization saga that **keeps money correct under failure**. Its signature property is
**provable zero drift** — a continuous reconciliation job and a property-based invariant test prove
the ledger sums to zero even when injected downstream failures occur. Built as a **modular
monolith** (`app`) with exactly one genuinely separate service, the **partner-simulator**, that
provides a real network boundary to fail against.

## The three invariants (non-negotiable — every change respects these)

1. **Balance** — every journal write is balanced; `SUM(journal entries) == 0` per currency, always.
   Unbalanced posts are rejected with a typed `BalanceInvariantViolation` and write nothing.
2. **Immutability** — entries are append-only. Corrections are **new compensating entries**, never
   `UPDATE`/`DELETE` on `journal_entry` (enforced by a DB trigger).
3. **Idempotency** — every mutating endpoint is safe to retry: a replay returns the original result,
   never a double-effect. Wrap mutating operations in the `IdempotencyGuard`.

The non-negotiable CI gate: for any random sequence of `auth / capture / reversal / fault`, the sum
of journal entries `== 0` (`recon/.../LedgerInvariantPropertyTest`). Never weaken it.

## Stack (ratified in Spec 00, ADR-0002)

| Concern | Choice |
|---------|--------|
| Language / runtime | Java 21, Spring Boot 4.0.x |
| Build | Maven multi-module reactor (`./mvnw`) |
| Database | PostgreSQL; Flyway migrations (append-only, never edit a shipped migration) |
| Testing | JUnit 5, Testcontainers Postgres for integration, jqwik for invariant properties |
| Money | integer **minor units** via `io.driftless.common.money.Money` (no float, ever) |
| Time | injected `java.time.Clock` (never `Instant.now()` / `new Date()` in business logic) |
| Metrics | Micrometer → Prometheus; Grafana provisioned as code |
| Packaging | Docker Compose one-command bring-up; k8s is an optional stretch |

## Module map (dependency direction points strictly inward toward `ledger`/`common`)

```
common ── kernel: Money, typed ids (AccountId/TxId/EntryId/TokenId), Clock, DomainException
  ▲
ledger ── Spec 01: immutable double-entry ledger. FROZEN api: io.driftless.ledger.api.Ledger,
          spi: io.driftless.ledger.spi.HoldView. Balance derived by summation; V2 trigger blocks
          UPDATE/DELETE on journal_entry.
  ▲
idempotency ── Spec 02: IdempotencyGuard (api) + OutboxWriter/OutboxEvent (api), transactional
               outbox + relay. Guard records key in the SAME tx as the business write.
rules ── Spec 05: RuleEngine.evaluate(AuthContext) -> RuleResult. Pure, no I/O on hot path
         (pre-compiled in-memory rules; p99 single-digit-ms). VelocityStore in-memory.
tokens ── Spec 06: TokenService lifecycle (api types already defined: Token, TokenStatus,
          CreateTokenCommand, IllegalTokenTransition).
auth ── Spec 03: authorization saga (authorize/capture/reverse), available-vs-posted, bounded-
        timeout compensating reversal. Implements ledger HoldView. Orchestrates rules → ledger →
        partner-simulator → capture/reversal, every step guarded by idempotency + outbox.
recon ── Spec 07: continuous balance-proof job, load generator, fault-injection harness, the
         property-based invariant test (THE GATE).
observability ── Spec 08: Micrometer metrics, Prometheus, provisioned Grafana zero-drift dashboard.
app ── modular monolith wiring every feature module; exposes REST + /actuator/prometheus.
partner-simulator ── Spec 04: SEPARATE service (own image/container). Injectable latency / failure /
                     timeout / fail-before-response / late-response. Idempotent partner endpoints.
```

Rules: features depend on `ledger`/`common`, **never on each other's internals** — integrate through
published `api`/`spi` packages and outbox events. No cycles.

## Key public contracts (code against these; do not duplicate)

- Ledger: `Ledger.openAccount/post/balanceOf/findTransaction/entriesFor`; `HoldView.activeHoldTotal`
  so `available = posted − activeHoldTotal`. `Money`, `AccountId`, `TxId` from `common`.
- Idempotency: `IdempotencyGuard.execute(key, requestHash, Supplier<StoredResult<T>>)` →
  `IdempotentResult<T>`; conflict on same key + different `requestHash`.
- Outbox: `OutboxWriter.append(OutboxEvent)` **inside the caller's transaction**.
- Rules: `RuleEngine.evaluate(AuthContext) -> RuleResult` (`Decision.APPROVE/DECLINE`).
- Tokens: `TokenService` (Spec 06) over `Token`/`TokenStatus`.
- Partner: HTTP only, reached by service name; under a hard bounded timeout in the saga.

## Conventions (all roles)

- Thin `@RestController` (routing + (de)serialization only); business logic in `@Service`; package
  by feature. Never return JPA entities from controllers — map to DTOs/records.
- Constructor injection via `final` fields (`@RequiredArgsConstructor`); no field `@Autowired`.
- `@ConfigurationProperties` POJOs over scattered `@Value`. Per-env `application-{dev,prod}.yml`.
- Centralized `@RestControllerAdvice` for errors. Validate inputs (`@Valid`/`@NotBlank`/`@Min`).
  Every mutating endpoint reads `Idempotency-Key` (missing ⇒ 400, same key + different body ⇒ 409,
  replay ⇒ original 2xx).
- SLF4J `{}` logging (`log.info("auth {} captured {}", id, amount)`) — never concatenation, never
  `System.out`. No raw PAN in logs or storage (store a reference/hash).
- Balance is derived by summation, never stored. Flyway migrations versioned and append-only.
- Search for an existing method/bean/util and reuse before writing new code.
- Never change correct existing business logic; fix incorrect logic deliberately and call it out.
- Do not change the frozen `io.driftless.ledger.api` shape without flagging a freeze reopen.

## Build / test commands

- Test: `./mvnw test`  ·  Full build + gates (Spotless + JaCoCo + invariant property):
  `./mvnw verify`  (Windows: `.\mvnw.cmd …`).
- Integration tests use Testcontainers → a running Docker daemon is required.
- Formatting: Spotless (palantir-java-format). Run `./mvnw spotless:apply` to fix style.

## Current state (delivered)

- Spec 00 Foundation — reactor + `common` kernel + frozen `ledger.api/spi` + ADRs + gates + CI.
- Spec 01 Ledger Core — `LedgerService`, JPA, Flyway V1/V2 (append-only trigger), default no-op
  `HoldView`, idempotent `post`.
- Spec 02 Idempotency & Outbox — guard + transactional outbox + relay.
- Spec 04 Partner Simulator — separate service with fault-injection control plane.
- Spec 05 Rule Engine — pre-compiled in-memory rules, velocity store, p99 latency tests.
- Spec 06 token `api` types defined (service/persistence pending).

In progress (this milestone): **03 Auth Saga, 06 Tokenization, 07 Reconciliation, 08 Observability,
09 Packaging**. Build order honoring dependencies: 06 → 03 → 07 → 08 → 09.

## Git / commit convention (IMPORTANT)

- **Always commit as `adan35 <adanshahzad35@gmail.com>`.** Never commit as Copilot or
  github-actions. Verify `git config user.name` is `adan35` before committing.
- Commits include the Copilot `Co-authored-by` trailer; authorship remains adan35.
- Commit step by step (one coherent change per commit), with a descriptive message.

## Documentation

- Architecture/decision docs live in `docs/` (ADRs, contracts — git-ignored, local) and **`.docs/`**
  (tracked, published project documentation: structure, business logic, why, examples).
- Specs → `.claude/specs/NN-*.md`, tests → `.claude/tests/NN-*.md`,
  reviews → `.claude/reviews/NN-*.md` (zero-padded counter from 00).
