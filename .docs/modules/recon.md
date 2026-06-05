# Module: `recon` — Reconciliation & Reliability (Spec 07)

> Where Driftless earns its tagline. This module **proves** zero drift continuously, generates load
> to exercise the system realistically, and injects faults to demonstrate that downstream failures
> never produce drift. **It owns the non-negotiable gate.**

## Why this module exists

Every other module *claims* correctness. This one *proves* it — and proof is the entire pitch.
Anyone can write a ledger that balances on the happy path; the question that matters in payments is:
"after a storm of retries, timeouts, duplicates, and crashes, do the books still sum to zero?"
`recon` answers that question continuously and turns the answer into evidence a reviewer can
reproduce.

It does three things that together convert "we claim zero drift" into "here is the proof":

1. A **continuous reconciliation / balance-proof job**.
2. A **load generator** that drives realistic traffic.
3. A **fault-injection harness** + the **property-based invariant test** — the CI gate.

## 1. The continuous reconciliation job

A scheduled job (and an on-demand endpoint) proves correctness by **summation over committed
state** — never by trusting a cached number:

| Check | What it asserts | Drift signal |
|-------|-----------------|--------------|
| **Global zero** | `SUM(debits) − SUM(credits) == 0` per currency across the whole `journal_entry` table | a non-zero total is drift |
| **Per-account consistency** | each account's entries reconcile (posted is purely derived by sum) | an account whose entries don't reconcile |
| **Hold consistency** | `available == posted − activeHoldTotal` for affected accounts | a hold that doesn't match available |
| **Outbox consistency** | no `PENDING` outbox event older than a threshold | a stuck relay (reliability defect) |

Each run emits a structured, **persisted** `ReconciliationResult` (timestamp from the injected
`Clock`, per-check pass/fail, total drift amount, offending accounts/transactions). It's exposed for
Spec 08's dashboard and for root-cause analysis via:

```
POST /reconciliation/run        -> run now, return the result
GET  /reconciliation/latest     -> the most recent result
GET  /reconciliation/{id}       -> a specific result
GET  /reconciliation/{id}/rca   -> an RCA report for a failed result
```

A `@Scheduled` job runs the same checks on a configurable interval.

**On detected drift it never auto-edits the ledger.** Drift is surfaced for deliberate, human-decided
compensation (a new compensating entry) — silently patching the books would defeat the entire point.

### The detector is proven to actually detect

A no-op checker that always says "zero drift" is worse than useless. So the module includes a test
that **introduces real drift** the only way drift can exist — by inserting a deliberately
**unbalanced** `journal_entry` row *directly via SQL*, bypassing the ledger's balanced `post()` (which
would reject it) — then runs the job and asserts it **flags the drift and names the offending
account/transaction.** The detector is falsifiable, and it passes that falsification.

## 2. The load generator

A driver that issues a realistic mix of `authorize / capture / reverse` against the saga at a
configurable **rate** and **concurrency**, with tunable distributions (approve/decline mix,
capture-vs-reverse ratio, amount ranges, MCCs). It records **throughput and latency** — feeding the
dashboard, backing the rule-engine p99 claim, and putting the system under realistic stress while
faults are injected.

## 3. The fault-injection harness + the property gate

The harness drives the **partner-simulator control plane** to inject `latency / timeout /
fail-before-response / decline / error-rate / duplicate / late-response` while load runs, and
simulates **process-level faults** (forcing the auth recovery sweep, dropping and recovering the
outbox relay).

### The property-based invariant test — THE GATE

This is the `LedgerInvariantPropertyTest` that Spec 00 wired and Spec 07 implements for real. Using
**jqwik**, it generates **random sequences** of `auth / capture / reversal / fault` operations against
the real ledger + auth saga (with a controllable partner). After each sequence **settles** (the
recovery sweep is driven to completion so every `COMPENSATING` obligation resolves), it asserts:

```
1. global SUM of journal entries == 0 per currency         (no drift)
2. no operation produced a double-effect                    (idempotency held)
3. every hold is active-with-matching-available or cleanly released  (no dangling hold)
```

A fixed seed makes failures reproducible; randomized runs in CI hunt for new ones. **This is the
gate** — green here is what lets the README *prove* zero drift instead of asserting it. It must never
be weakened or deleted.

```
        ┌─────────────┐   random auth/capture/reverse/fault   ┌──────────────┐
        │ jqwik       │──────────────────────────────────────▶│ ledger + auth│
        │ generator   │                                        │ saga + stub  │
        └─────────────┘                                        │ partner      │
                                                               └──────┬───────┘
              settle recovery sweep ◀──────────────────────────────── │
                              │
                              ▼
        assert: ∑ entries == 0  ∧  no double-effect  ∧  no dangling hold
```

## How the invariants are verified here (continuously)

| Invariant | Verification |
|-----------|--------------|
| **Balance** | the global-zero check + the property test assert `∑ == 0` after every sequence. |
| **Immutability** | the proof reads append-only history; any detected mutation is a failure. |
| **Idempotency** | the fault harness retries/duplicates operations and asserts no double-effect. |

## RCA

From any failed `ReconciliationResult`, a short RCA-style report can be produced: *what* drifted (the
amount and currency), *where* (the offending accounts/transactions), and a *candidate cause* — the
starting point for a deliberate compensating correction.

## Out of scope

- Visualization (Spec 08 renders these results on the Grafana "zero drift" dashboard).
- Auto-remediation of drift (always surfaced for deliberate human compensation, never silently
  patched).
