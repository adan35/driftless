# The Three Invariants — How Each Is Enforced

Driftless treats three properties as **correctness, not style**. They hold everywhere money moves,
and the test suite is built to *falsify* them if they ever break. This page maps each invariant to
the concrete code and tests that hold the line.

---

## Invariant 1 — Balance

> Every journal write is balanced; the signed sum of all entries is always **zero** (per currency).
> Unbalanced posts are rejected and write nothing.

**Where it lives**

- `io.driftless.ledger.api.PostingRequest` requires **≥2 lines**.
- `io.driftless.ledger.api.PostingLine` requires a **strictly positive** `Money`; the `Direction`
  (`DEBIT`/`CREDIT`) carries the sign — you cannot smuggle a negative amount.
- `LedgerService.post(...)` groups lines by currency, sums debits and credits, and throws
  `BalanceInvariantViolation` (writing **nothing**) unless every currency nets to zero.

**Why it can't be faked**

There is no `balance` column. A balance is *always* `SUM(...)` over `journal_entry`, signed by
direction (see [`03-double-entry-ledger.md`](./03-double-entry-ledger.md) §5). So "the balance" and
"the history" cannot disagree — they are the same query.

**How it's tested**

- Unit: an unbalanced `PostingRequest` is rejected; no rows written.
- Property (jqwik, the CI gate): for random sequences of `auth/capture/reversal/fault`,
  `SUM(signed amount) over ALL entries == 0`.

---

## Invariant 2 — Immutability

> Entries are append-only. A correction is a **new compensating entry**, never an edit or delete.

**Where it lives**

- No code path performs `UPDATE`/`DELETE` on `journal_entry`. The repository exposes inserts and
  reads only.
- Flyway `V2__journal_entry_append_only.sql` installs a **`BEFORE UPDATE OR DELETE` trigger** that
  raises an exception — so even a hand-written SQL statement or a future bug cannot mutate history.
- Reversals are implemented as **new `post()` calls** that compensate, not as edits of the original
  transaction.

**Why it matters**

Auditability and provability. If history can be rewritten, "the sum is zero" proves nothing. Because
the past is frozen, the reconciliation job can trust what it reads, and a detected discrepancy is
*real*, not an artifact of a racing update.

**How it's tested**

- Integration (Testcontainers Postgres, real trigger): a direct `UPDATE` and a direct `DELETE`
  against `journal_entry` are both rejected by the database.
- Saga tests: a reversal adds a new compensating transaction and leaves the original rows
  byte-identical.

---

## Invariant 3 — Idempotency

> Every mutating operation is safe to retry: a replay returns the original result, never a
> double-effect.

**Where it lives**

- `io.driftless.idempotency.api.IdempotencyGuard.execute(key, requestHash, operation)` runs the
  operation **at most once per key**. The idempotency record is written **in the same DB
  transaction** as the business write, so the side effect and the guard commit or roll back together.
- A concurrent duplicate is caught by a **unique constraint** on the record; the loser reads and
  returns the winner's stored result.
- A key reused with a **different** `requestHash` is an `IdempotencyConflict` (HTTP 409) — never a
  silent replay of a different request.
- At the ledger level, `transaction.idempotency_key` is itself `UNIQUE`, so a replayed `post()`
  collides at the database and returns the original `PostingResult` with `replayed=true`.

**Why it matters**

Networks retry. Clients retry. Crashes force retries. Without idempotency, every retry risks a double
charge or a double release. With it, "authorize/capture/reverse" can be re-sent freely and the system
converges to exactly one effect.

**How it's tested**

- Concurrency: two threads racing the same key produce one effect and one stored result.
- Replay: re-sending `authorize/capture/reverse` with the same key yields the original response and
  **no** new ledger rows.
- Conflict: same key + different body ⇒ 409.
- Crash-safety: the saga left at any step boundary recovers to a consistent state (no dangling hold,
  no half-posted capture) — exercised by the Spec 07 fault harness.

---

## How the three combine into "provable zero drift"

```
Balance      → every transaction nets to zero, so the global sum starts and stays at zero.
Immutability → the history the proof reads cannot be rewritten, so the proof is trustworthy.
Idempotency  → retries and late/duplicate responses converge to exactly one effect, so failures
               and recoveries don't add phantom money.
                              │
                              ▼
   For ANY random sequence of auth/capture/reversal/fault:  ∑ journal entries == 0.
   That property, asserted continuously and in CI, is the entire pitch.
```

The non-negotiable gate is the property-based invariant test in `recon`
(`LedgerInvariantPropertyTest`). It is wired into `./mvnw verify`. **Never weaken it** — a change
that makes the gate pass by relaxing the assertion is a regression, not a fix.
