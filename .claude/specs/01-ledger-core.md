# Spec 01 — Ledger Core

| Field | Value |
|-------|-------|
| **Owns** | Double-entry journal, accounts, balanced-entry + immutability invariants, balance-by-summation |
| **CV story** | The foundation |
| **Depends on** | Spec 00 (foundation, `common` module) |
| **Blocks** | 02, 03, 04, 05, 06, 07 (everything money-related) |
| **Parallelizable** | No — sequential gate immediately after Spec 00 |

## North star

The immutable double-entry ledger is the heart of Driftless. Everything else moves money *through*
it. Signature property of the whole system: **provable zero drift**, which is only possible
because this module is correct by construction.

## The three invariants (this module **is** invariants 1 & 2)

1. **Balance invariant** — every journal write is balanced; the sum of all entries is always zero.
2. **Immutability** — entries are append-only; corrections are new compensating entries, never
   edits/deletes.
3. **Idempotency** — `post()` is safe to retry; a replay returns the original result (full
   idempotency machinery is Spec 02, but the ledger must accept and honor an idempotency key on
   `post`).

## Goal

Implement a correct, append-only, double-entry ledger with strongly-typed accounts and
balance-by-summation. **Define and publish the frozen public interface** (`io.driftless.ledger.api`)
that all downstream modules code against. Correctness here is the foundation for the zero-drift
proof.

## Public interface — **FREEZE THIS** (Spec 00 marks it frozen before the parallel batch)

This is the contract downstream agents depend on. Names/shapes may be refined during 00/01 review,
but once frozen they do not change without re-opening the gate.

```java
package io.driftless.ledger.api;

/** Debit or Credit. Sign convention is explicit and lives here, not in callers. */
public enum Direction { DEBIT, CREDIT }

public enum AccountType {
    ASSET,      // e.g. settlement/funding asset
    LIABILITY,  // e.g. cardholder funds the issuer owes
    EQUITY,
    REVENUE,
    EXPENSE
}

/** Immutable account definition. */
public record Account(
    AccountId id,
    AccountType type,
    Currency currency,
    String name) {}

/** One leg of a balanced transaction. amount is always positive minor units; Direction carries sign. */
public record PostingLine(
    AccountId account,
    Direction direction,
    Money amount) {}        // Money.currency MUST match the account's currency

/** A request to post one balanced, atomic transaction (>= 2 lines). */
public record PostingRequest(
    String idempotencyKey,        // honored: replay returns the original TxId/result
    Instant occurredAt,           // business time (from injected Clock at the caller)
    String description,
    List<PostingLine> lines) {}   // SUM(debits) == SUM(credits) per currency, else rejected

/** Result of a successful (or replayed) post. */
public record PostingResult(
    TxId transactionId,
    Instant postedAt,
    boolean replayed) {}          // true when returned from an idempotent replay

/** Posted vs available is surfaced here; available accounts for holds (see Spec 03). */
public record Balance(
    AccountId account,
    Currency currency,
    Money posted,                 // sum of settled journal entries
    Money available) {}           // posted adjusted for active holds; == posted when no holds

public interface Ledger {

    /** Create an account. Idempotent on a caller-supplied id. */
    Account openAccount(Account account);

    /**
     * Atomically post one balanced transaction.
     * - Rejects unbalanced requests (BalanceInvariantViolation).
     * - Rejects currency mismatch between a line's Money and its account.
     * - Append-only: never updates/deletes existing entries.
     * - Idempotent: same idempotencyKey returns the original PostingResult with replayed=true.
     */
    PostingResult post(PostingRequest request);

    /** Posted + available balance for an account, computed by summation over entries (+ holds). */
    Balance balanceOf(AccountId account);

    /** Read a posted transaction and its entries (immutable view). */
    Transaction findTransaction(TxId id);

    /** Stream/page entries for an account (for reconciliation and statements). */
    List<JournalEntry> entriesFor(AccountId account, Page page);
}
```

> **Holds / available balance:** the *concept* of available balance lives here (the `Balance`
> record exposes it), but **hold lifecycle** (place/release/expire) is owned by Spec 03. Provide
> a minimal SPI so Spec 03 can register active holds that `balanceOf` subtracts from `available`.
> Define this SPI now so 03 doesn't have to reopen the freeze:
>
> ```java
> package io.driftless.ledger.spi;
> /** Spec 03 implements/feeds this; ledger uses it to compute `available`. */
> public interface HoldView {
>     Money activeHoldTotal(AccountId account, Currency currency);
> }
> ```

## Data model

- **`account`** — id, type, currency, name, created_at. Immutable after creation (no balance
  column; balance is derived).
- **`transaction`** (a.k.a. journal batch) — id, idempotency_key (unique), occurred_at, posted_at,
  description.
- **`journal_entry`** — id, transaction_id (FK), account_id (FK), direction, amount_minor,
  currency, sequence_no. **Append-only**: no `UPDATE`/`DELETE` paths in code; enforce with a
  DB rule/trigger or a revoked-privilege migration where practical.
- Index `journal_entry(account_id)` for summation; unique index on `transaction.idempotency_key`.
- Balance is **never stored** — always `SELECT SUM(...)` by direction. (A snapshot/cache may be
  added later by Spec 07 for performance, but it is derived and reconcilable, never authoritative.)

## Correctness rules

- A `PostingRequest` is rejected unless, **per currency**, `SUM(amount where DEBIT) ==
  SUM(amount where CREDIT)`. (Multi-currency transactions are balanced independently per currency;
  default MVP can be single-currency per transaction — document the choice.)
- Every `PostingLine.amount` is strictly positive; direction carries the sign.
- A line's `Money.currency` must equal its account's currency.
- `post` is transactional: either all entries land or none do.
- Corrections are new `post` calls with reversing direction — never a mutation of an existing
  entry.

## Acceptance criteria (falsifiable)

- [ ] `post` rejects an unbalanced request with a typed `BalanceInvariantViolation` and writes
      nothing.
- [ ] `post` rejects a line whose currency differs from its account.
- [ ] A balanced 2-line post produces two `journal_entry` rows and a `transaction` row.
- [ ] Replaying a `post` with the same `idempotencyKey` returns the original `TxId` with
      `replayed=true` and creates **no** new rows.
- [ ] `balanceOf` equals the summation of entries; with no holds, `available == posted`.
- [ ] With a `HoldView` reporting active holds, `available == posted - activeHoldTotal`.
- [ ] There is no code path that issues `UPDATE`/`DELETE` against `journal_entry`; an attempt at
      DB level is rejected (trigger/privilege) — covered by an integration test.
- [ ] **The sum of all journal entries across the whole ledger is zero** at all times — proven by
      a test that posts a random valid sequence and asserts the global sum is 0. (This is the seed
      of the Spec 07 property test; implement a first version here.)
- [ ] Integration tests run against real Postgres via Testcontainers.

## Gate

01 is a **sequential gate**. After it passes review, Spec 00 marks the published interface
**frozen**, and only then are 02 / 04 / 05 / 06 generated and run in parallel.

## Out of scope

- Idempotency *storage/replay machinery* beyond honoring the key on `post` (Spec 02 generalizes it).
- Hold lifecycle (place/expire/release) and the auth saga (Spec 03) — only the `HoldView` SPI and
  the `available` field live here.
- Events/outbox (Spec 02), rules (Spec 05), tokens (Spec 06).
