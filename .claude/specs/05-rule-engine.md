# Spec 05 — Rule Engine

| Field | Value |
|-------|-------|
| **Owns** | Hot-path limits / velocity / MCC evaluation; pre-compiled rules cached off the DB path; p99 target |
| **CV story** | DuringPurchaseIssuerMerchant |
| **Depends on** | Spec 00 (foundation); reads balances via frozen ledger API (Spec 01) |
| **Blocks** | Spec 03 (auth saga calls the engine on the hot path) |
| **Parallelizable** | Yes — parallel batch with 02 / 04 / 06 (mostly independent of ledger internals) |

## North star

Authorization decisions happen *during the purchase*, on the hot path, with a hard latency
budget. The rule engine decides approve/decline for limits, velocity, and MCC restrictions in
**single-digit milliseconds** — which means rules are pre-compiled and cached **off** the database
path, not evaluated by querying the DB per transaction.

## The three invariants (how they apply here)

The rule engine is a **decision** component — it reads state but moves no money, so it cannot
violate the balance or immutability invariants directly. It must, however, be **deterministic and
idempotent**: evaluating the same context twice yields the same decision and records no
double-counted velocity. Velocity counters are the one piece of mutable state and must be updated
exactly once per authorization (coordinate with Spec 03's idempotency).

## Goal

A fast, deterministic rule-evaluation engine invoked by the auth saga before a hold is placed.
Rules are authored as data, **compiled** into an in-memory representation, and evaluated against an
authorization context without touching the DB on the hot path.

## Public interface (called by Spec 03 on the hot path)

```java
package io.driftless.rules.api;

public record AuthContext(
    AccountId account,
    Money amount,
    String mcc,              // merchant category code
    String merchantId,
    Instant requestedAt,
    Money availableBalance,  // supplied by the saga from the ledger (no DB call inside the engine)
    VelocitySnapshot velocity) {}

public enum Decision { APPROVE, DECLINE }

public record RuleResult(
    Decision decision,
    String ruleId,          // which rule decided (for DECLINE) — null/empty on clean APPROVE
    String reasonCode) {}   // machine-readable reason, e.g. "LIMIT_EXCEEDED", "VELOCITY", "MCC_BLOCKED"

public interface RuleEngine {
    /** Pure, fast, deterministic. No I/O on this call. */
    RuleResult evaluate(AuthContext context);
}
```

## Rule types (MVP)

- **Limits** — per-transaction max, daily/periodic spend cap (uses `availableBalance` and
  velocity totals).
- **Velocity** — count/sum of transactions in a rolling window (e.g. N txns / M minutes, or
  $X / day). Counters are kept in a fast store (in-memory + periodic persistence, or Redis-style
  seam) — **not** a per-auth DB aggregation query.
- **MCC** — allow/block lists by merchant category code.

## Performance design (the point of this spec)

- Rules are stored as data (DB/config) but **compiled** at load time into an evaluable form
  (e.g. predicate objects / a small compiled rule set) held in memory.
- A **rule cache** is refreshed off the hot path (on change / on interval); evaluation reads only
  in-memory state.
- Velocity snapshots are passed into `evaluate` (the saga reads them), or read from an in-memory
  counter — never computed by a DB `GROUP BY` during authorization.
- **p99 latency target:** evaluation completes in **single-digit milliseconds** under load.
  Provide a JMH or load-test-backed measurement proving it.

## Acceptance criteria (falsifiable)

- [ ] `evaluate` performs **no database I/O** (verified by test: run with the DB unavailable / via
      an I/O-detecting harness, or by construction with no datasource injected into the hot path).
- [ ] A transaction over the per-txn limit → `DECLINE` with `reasonCode=LIMIT_EXCEEDED`.
- [ ] Exceeding velocity (N in window) → `DECLINE` with `reasonCode=VELOCITY`.
- [ ] A blocked MCC → `DECLINE` with `reasonCode=MCC_BLOCKED`.
- [ ] A clean transaction → `APPROVE`.
- [ ] `evaluate` is deterministic: same `AuthContext` → same `RuleResult`.
- [ ] Rule changes take effect after a cache refresh **without** restart.
- [ ] **p99 of `evaluate` is single-digit ms** under a representative load, demonstrated by a
      committed benchmark/load test with the numbers recorded.
- [ ] Velocity counters are not double-incremented on a retried authorization (coordinate with
      Spec 02/03 idempotency).

## Gate

QA agent verifies the p99 target with evidence. The decision path must not become a hidden
DB dependency that the fault-injection harness (Spec 07) can stall.

## Out of scope

- Placing the hold / posting to the ledger (Spec 03 acts on the decision).
- A rule-authoring UI — rules as config/data is sufficient for MVP.
