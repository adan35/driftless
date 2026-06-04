# Driftless — Project Overview & Business Case

> **One sentence.** Driftless is an open-source issuer-processor core — an immutable double-entry
> ledger plus a card-authorization saga — engineered so that **money stays provably correct even
> when downstream systems fail.**

## 1. Why this project exists

When you tap a card, a chain of systems must agree, to the cent, on what just happened: your bank
(the **issuer**), the merchant's bank (the **acquirer**), the networks (Visa/Mastercard), and a
**processor** that runs the authorization and keeps the books. The hard part is not the happy path.
The hard part is **failure**: a network call that times out *after* the other side already acted, a
process that crashes between "place a hold" and "record the charge", a retry that risks charging
twice. Each of these can leave the books **drifted** — the ledger says one thing, reality says
another — and in payments, drift is real money lost or conjured.

The industry has expensive, public examples of exactly this class of bug:

- **Phantom holds** — an authorization hold that is never released because the confirming call
  failed, silently freezing a customer's available balance.
- **Captured-but-unconfirmed charges** — the processor settled a charge but never heard back, so a
  retry double-charges, or a reversal never fires.
- **Reconciliation gaps** — end-of-day totals that don't sum to zero, discovered hours later, with
  no automated proof of where the discrepancy entered.

Driftless is a focused reference implementation that **solves this correctly** and, crucially,
**proves it**. Its signature property is *provable zero drift*: a continuous reconciliation job and
a property-based invariant test demonstrate that for **any** random sequence of
`authorize / capture / reversal / fault`, the sum of all journal entries is exactly zero.

## 2. The pitch in one diagram

```
        Cardholder taps                       Money must stay correct
              │                                under EVERY failure mode
              ▼                                          │
     ┌──────────────────┐   rules    ┌──────────────┐   │   ┌─────────────────────┐
     │  Auth Saga (03)  │──────────▶│ Rule Engine   │   │   │ Partner Simulator   │
     │ authorize/capture │ <1ms p99  │   (05)        │   │   │  (04, separate svc) │
     │ reverse + compen- │           └──────────────┘   │   │  real network hop,  │
     │ sating reversal   │───────── bounded-timeout call ────▶│ injectable faults  │
     └────────┬─────────┘                                    └─────────────────────┘
              │ every step: balanced post + idempotency guard + outbox event
              ▼
     ┌──────────────────┐         ┌──────────────────────┐      ┌────────────────────┐
     │ Ledger (01)      │◀────────│ Reconciliation (07)  │─────▶│ Observability (08) │
     │ append-only      │  proves │ continuous balance   │ feeds│ "zero drift"       │
     │ double-entry     │  ∑ == 0 │ proof + fault harness │      │ Grafana dashboard  │
     └──────────────────┘         └──────────────────────┘      └────────────────────┘
```

## 3. What makes it different

| Most demos | Driftless |
|------------|-----------|
| Store a `balance` column and mutate it | Balance is **derived by summation** over immutable entries; it can never silently drift from its history |
| "Handle errors" with try/catch | A **saga** with a **bounded-timeout compensating reversal**: a stuck partner call can never leave a phantom hold |
| Assert idempotency in prose | An **idempotency guard** that records the key in the *same DB transaction* as the business write, plus a transactional **outbox** for exactly-once events |
| Claim correctness | **Proves** it: a property-based test over random `auth/capture/reverse/fault` sequences asserts `∑ entries == 0`, wired into CI as a non-negotiable gate |
| One process pretending to be distributed | A genuinely **separate** partner service over a real network boundary, with a fault-injection control plane |

## 4. The three invariants (the spine of the whole system)

Every line of money-moving code respects these. They are treated as **correctness, not style**.

1. **Balance** — every journal write is balanced; the sum of all entries is always zero (per
   currency). Unbalanced posts are rejected and write nothing.
2. **Immutability** — entries are append-only. A correction is a **new compensating entry**, never an
   edit or delete. Enforced in code *and* by a database trigger.
3. **Idempotency** — every mutating operation is safe to retry: a replay returns the original result,
   never a double-effect.

See [`04-invariants.md`](./04-invariants.md) for how each is enforced, with code references.

## 5. Who this is for

- **Engineers** learning how real issuer-processors keep money correct (double-entry, sagas,
  outbox, reconciliation) in a compact, readable, fully-tested codebase.
- **Reviewers / hiring managers** who want to *see* the zero-drift property reproduced on their own
  machine in minutes (`docker compose up`, run the fault demo, watch drift hold at $0).
- **Teams** wanting a reference architecture for a modular monolith that is microservices-ready
  without paying distributed-systems costs prematurely.

## 6. Map of the documentation

| Doc | What it covers |
|-----|----------------|
| [`01-overview.md`](./01-overview.md) | This file — why Driftless exists and the business case |
| [`02-architecture.md`](./02-architecture.md) | Project structure, module map, dependency rules, request lifecycle |
| [`03-double-entry-ledger.md`](./03-double-entry-ledger.md) | The core accounting model with worked examples |
| [`04-invariants.md`](./04-invariants.md) | Balance / Immutability / Idempotency — how each is enforced |
| [`modules/`](./modules/) | One deep-dive per module/spec (why it exists, business logic, worked example) |

Each module page answers the same three questions the project cares about: **why is this needed**,
**what is the business logic**, and **show me an example**.
