# Spec 07 — Reconciliation & Reliability

| Field | Value |
|-------|-------|
| **Owns** | Continuous balance-proof job, load generator, fault-injection harness |
| **CV story** | Reconciliation, RCA |
| **Depends on** | Spec 01 (ledger), Spec 03 (auth saga), Spec 04 (partner simulator), Spec 02 (idempotency/outbox) |
| **Blocks** | Spec 08 (dashboard reads recon results) |
| **Parallelizable** | No — runs after 03 lands |

## North star

This spec is where Driftless earns its tagline. It **proves** zero drift continuously, generates
load to exercise the system realistically, and injects faults to demonstrate that downstream
failures never produce drift. The output is the evidence the README links to. **This spec owns the
non-negotiable gate.**

## The three invariants (this spec verifies them, continuously)

1. **Balance invariant** — the continuous proof job asserts the global sum of journal entries is 0.
2. **Immutability** — the proof reads append-only history; any detected mutation is a failure.
3. **Idempotency** — the fault harness retries/duplicates operations and asserts no double-effect.

## Goal

Three deliverables that together turn "we claim zero drift" into "here is the proof":

1. **Continuous reconciliation / balance-proof job.**
2. **Load generator.**
3. **Fault-injection harness** + the **property-based invariant test** (the CI gate).

## 1. Continuous reconciliation job

- A scheduled job (and an on-demand endpoint) that proves, by summation over `journal_entry`:
  - **Global zero:** `SUM(debits) - SUM(credits) == 0` across the whole ledger.
  - **Per-account consistency:** each account's summed balance matches any cached snapshot (if a
    snapshot cache exists); discrepancies are drift.
  - **Hold consistency:** active holds reconcile with `available` balances.
  - **Outbox consistency:** no `PENDING` event older than a threshold (stuck relay = reliability
    issue).
- Emits a structured **reconciliation result** (timestamp, checks run, pass/fail, any drift amount
  and offending accounts) — persisted and exposed for Spec 08's dashboard and for RCA.
- On detected drift: mark the result failed, surface the offending transactions, and expose enough
  detail to do a root-cause analysis. **Never auto-edit the ledger to "fix" it** — corrections are
  new compensating entries, decided deliberately.

## 2. Load generator

- A driver that issues realistic mixes of `authorize / capture / reverse` against the running
  system at a configurable rate and concurrency.
- Configurable distributions (approve/decline mix, capture vs reverse ratio, amount ranges, MCCs).
- Records throughput and latency; feeds the dashboard. Used both for the p99 rule-engine check
  (Spec 05) and to run the system under stress during fault injection.

## 3. Fault-injection harness + property test (THE GATE)

- Drives the **partner simulator** (Spec 04) control plane to inject latency / timeout /
  fail-before-response / duplicate / late-response while the load generator runs.
- Also injects **process-level faults** where feasible: kill/restart `app` between saga steps,
  drop the outbox relay, etc.
- **Property-based invariant test (CI gate):** generate **random sequences** of
  `auth / capture / reversal / fault` and assert, after each sequence settles, that:
  - the global sum of journal entries `== 0` (no drift),
  - no operation produced a double-effect (idempotency held),
  - every hold is either active-with-matching-available or cleanly released.
  Use a property-based framework (e.g. jqwik) with a fixed seed for reproducibility plus randomized
  runs in CI. This is the `LedgerInvariantPropertyTest` stub Spec 00 wired into the build —
  implement it for real here.

## Acceptance criteria (falsifiable)

- [ ] The reconciliation job runs on a schedule and on demand, and produces a persisted result.
- [ ] After any load run with **no** faults, reconciliation reports **zero drift**.
- [ ] After load runs **with** injected faults (timeout, fail-before-response, duplicates, process
      kills), reconciliation **still** reports zero drift — demonstrating the system self-heals via
      compensation/idempotency.
- [ ] The reconciliation job **detects** an artificially introduced drift (e.g. a deliberately
      unbalanced posting in a test fixture) and reports it with the offending account/transaction —
      proving the detector actually works (not a no-op that always passes).
- [ ] The load generator sustains a configurable rate and reports throughput + latency.
- [ ] **The property-based invariant test passes in CI**: for randomized sequences of
      auth/capture/reversal/fault, sum of journal entries `== 0` and no double-effects. This is the
      gate.
- [ ] A short RCA-style report can be produced from a drift detection (what, where, candidate
      cause).

## Gate (this spec **is** the gate)

The **reconciliation-correctness agent** plus the property test must be green for the milestone to
ship. Green here is what lets the README *prove* zero drift instead of asserting it.

## Out of scope

- The visualization (Spec 08 renders these results).
- Auto-remediation of drift (drift is surfaced for human/deliberate compensation, never silently
  patched).
