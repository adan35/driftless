# Module: `rules` — Hot-Path Authorization Decisions (Spec 05)

> The fast "yes/no" the saga asks before it touches money. Limits, velocity, and MCC rules evaluated
> in **single-digit milliseconds (p99)** with **no database query on the call.**

## Why this module exists

Every authorization needs an instant risk decision: is this within the per-transaction limit? Has
this account spent too much, too fast (velocity)? Is this merchant category blocked? Done naively —
a `GROUP BY` over the ledger per authorization — this decision becomes the system's slowest, most
contended step, exactly on the latency-critical path a cardholder is waiting on at the terminal.

Driftless makes the decision a **pure function of in-memory state**. Rules are pre-compiled and
cached off the database path; the engine reads only the supplied context and the compiled rules, so
it does **no I/O** and returns deterministically.

## The contract

```java
public interface RuleEngine {                          // io.driftless.rules.api
    RuleResult evaluate(AuthContext context);
}
```

- `AuthContext` carries **everything** the decision needs, assembled by the saga *before* the call:
  account, amount, mcc, merchantId, `requestedAt` (from the injected `Clock`), the account's
  `availableBalance` (read from the ledger by the saga, not the engine), and a `VelocitySnapshot`.
- `RuleResult` is `APPROVE`, or `DECLINE` with the **deciding rule id** and a machine-readable
  **reason code** (`LIMIT_EXCEEDED`, `VELOCITY`, `MCC_BLOCKED`). The first declining rule in
  deterministic priority order wins; if none fires, it's a clean approve.
- **Pure & deterministic:** two `AuthContext`s that are `equals` always yield the same `RuleResult`.

## Business logic

Rule sets come from `@ConfigurationProperties` (`driftless.rules`) and are **compiled** into an
in-memory form that the hot path evaluates directly:

| Rule kind | Decides on | Reason code |
|-----------|-----------|-------------|
| Per-transaction limit | single amount vs a cap | `LIMIT_EXCEEDED` |
| Periodic cap | summed window amount vs a cap | `LIMIT_EXCEEDED` |
| Velocity | count/total in the rolling window | `VELOCITY` |
| MCC | merchant category allow/block | `MCC_BLOCKED` |

A `RuleCache` holds the compiled rule set and refreshes on an interval (`refreshInterval`, default
30s) **off** the hot path — so a config change rolls in without ever making `evaluate` touch a
database.

### The velocity seam

```java
public interface VelocityStore {                       // io.driftless.rules.velocity
    boolean record(String authorizationId, AccountId account, Money amount, Instant occurredAt); // idempotent
    VelocitySnapshot snapshot(AccountId account, Currency currency, Instant now);                  // in-memory window
}
```

Two properties the saga relies on: `record` is **idempotent** on the authorization id (a retried
authorization is counted once), and `snapshot` answers from an **in-memory** rolling window — never a
`GROUP BY` per authorization. The MVP is `InMemoryVelocityStore`; a Redis-backed implementation could
replace it behind this interface without touching the engine or the saga.

## Worked example

```
AuthContext{ amount=$250, available=$1,000, mcc="7995" (gambling, blocked),
             velocity={count: 3, total: $400} }
   │
   ▼  evaluate (no I/O)
RuleResult.decline(ruleId="mcc-block-7995", reasonCode="MCC_BLOCKED")
```

The saga sees `DECLINE`, short-circuits, and **places no hold and moves no money** — the cheapest
possible path for a rejected authorization.

## Why "no DB on the hot path" is tested, not assumed

The module includes a test that fails if the evaluation path performs a database query, plus a
latency benchmark asserting the **p99 single-digit-millisecond** target. Spec 08 surfaces that p99 on
the dashboard so the claim is continuously verifiable, not just asserted once.

## Tests

Determinism property (jqwik): equal contexts ⇒ equal results; edge cases at exact limits; the
"no database on the hot path" guard; the latency benchmark; and the velocity store's idempotent
recording + windowing.
