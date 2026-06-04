# Driftless — Spec Set

> **North star.** Driftless is an open-source issuer-processor core: an immutable double-entry
> ledger plus a card-authorization engine that keeps money correct under failure. It models the
> real distinction between **available** and **posted** balance, runs the full
> auth → capture → reversal lifecycle with bounded-timeout compensating reversals, evaluates
> hot-path rules (limits / velocity / MCC) in single-digit milliseconds, and manages a
> tokenization lifecycle. Its signature property is **provable zero drift**: a continuous
> reconciliation job proves the ledger sums to zero, and a fault-injection harness demonstrates
> that injected downstream failures never produce drift. Built as a **modular monolith**
> (microservices-ready) with one genuinely separate service — a **partner simulator** — that
> provides a real network boundary to fail against.

This folder is the source of truth handed to the build agents. Read this index first, then your
assigned spec. Every spec restates the three invariants below — they are non-negotiable and apply
everywhere money moves.

## The three invariants (global — every spec respects these)

1. **Balance invariant** — every journal write is balanced; the sum of all entries is always zero.
2. **Immutability** — entries are append-only; corrections are new compensating entries, never
   edits/deletes.
3. **Idempotency** — every mutating endpoint is safe to retry; a replay returns the original
   result, never a double-effect.

## The non-negotiable gate

A **reconciliation-correctness agent** plus a **property-based invariant test in CI**: for any
random sequence of `auth / capture / reversal / fault`, the sum of journal entries `== 0`.
Green that gate and the README *proves* zero drift instead of asserting it. That is the whole
pitch.

## The 10 specs

| # | Spec | Owns | Maps to CV story |
|---|------|------|------------------|
| 00 | [Foundation & Migration](./00-foundation-migration.md) | single-pom → parent + modules; module boundaries, conventions, ADRs, quality gates → produces `CLAUDE.md` | spec-driven workflow |
| 01 | [Ledger Core](./01-ledger-core.md) | double-entry journal, accounts, balanced-entry + immutability invariants, balance-by-summation | the foundation |
| 02 | [Idempotency & Outbox](./02-idempotency-outbox.md) | idempotency keys, replay guard, transactional outbox for exactly-once events | FinBridge idempotency, hardened |
| 03 | [Auth Lifecycle Saga](./03-auth-lifecycle-saga.md) | auth/hold → capture → reversal, available vs posted, bounded timeout + compensating reversal | BHN $20K drift fix |
| 04 | [Partner Simulator](./04-partner-simulator.md) | separate service; injectable latency / failure / timeout on an external leg | BHN / Trimet SLA mismatch |
| 05 | [Rule Engine](./05-rule-engine.md) | hot-path limits / velocity / MCC eval, pre-compiled rules cached off the DB path, p99 target | DuringPurchaseIssuerMerchant |
| 06 | [Tokenization Lifecycle](./06-tokenization-lifecycle.md) | create / activate / suspend / resume / deactivate + status propagation | Wallet Token Activation (VTS) |
| 07 | [Reconciliation & Reliability](./07-reconciliation-reliability.md) | continuous balance-proof job, load generator, fault-injection harness | reconciliation, RCA |
| 08 | [Observability & Dashboard](./08-observability-dashboard.md) | Prometheus metrics, Grafana, the public read-only "zero drift" dashboard | Splunk/Grafana/Dynatrace |
| 09 | [Packaging & Deploy](./09-packaging-deploy.md) | `docker compose up` one-command bring-up now; k8s manifests as optional stretch section | Docker, basic k8s |

## Build / agent ordering

```
00 Foundation+Migration ──(gate: blocks all)──▶ 01 Ledger Core ──(gate)──┐
                                                                          │
        ┌────────────────────────┬────────────────────┬─────────────────┤ (parallel)
        ▼                        ▼                    ▼                  ▼
 02 Idempotency+Outbox    04 Partner Simulator   05 Rule Engine    06 Tokenization
        └────────────┬───────────┘                    │                  │
                     ▼                                 │                  │
            03 Auth Lifecycle Saga ◀───────────────────┘                  │
                     │                                                     │
                     ├─────────────────────────────────────────────────────┘
                     ▼
        07 Reconciliation+Reliability ──▶ 08 Observability+Dashboard ──▶ 09 Deploy
                     ▼
   sequential gates: QA agent · security agent · reconciliation-correctness agent
```

**Sequencing rule that makes parallelism safe:** Spec 00 freezes Spec 01's *public ledger
interface* before any parallel agent starts. 02 / 04 / 05 / 06 then run against a frozen API
rather than a moving one. 01 is a hard sequential gate (everything money-related depends on it);
05 (Rules) and 06 (Tokens) are mostly independent of ledger internals and run in the parallel
batch once 01's interfaces are frozen.

**Generation order:** 00 → 01 → review & freeze → 02 / 04 / 05 / 06 (parallel batch) → 03 →
07 / 08 / 09.

## Conventions used across specs

- **Stack (assumed; ratified in Spec 00):** Java 21, Spring Boot, Maven multi-module,
  PostgreSQL, Flyway migrations, Prometheus + Grafana, Docker Compose. Spec 00 records the final
  ADR; if it deviates, downstream specs follow Spec 00.
- **Money** is stored as integer **minor units** (`long`) with an explicit ISO-4217 currency.
  No floating point touches a balance, ever.
- Each spec is self-contained for its owning agent: it restates the invariants, lists falsifiable
  acceptance criteria, and names the CI gate it must pass.
