# Module: `ledger` — Immutable Double-Entry Ledger (Spec 01)

> The foundation. Every other module moves money through this one. Its public contract
> (`io.driftless.ledger.api.Ledger` + `spi.HoldView`) is **frozen** — downstream modules code
> against it and it does not change shape without reopening a review gate.

## Why this module exists

A payments system's books must have exactly one source of truth, and that source must be impossible
to silently corrupt. Most systems store a `balance` column and mutate it on every transaction — which
means a missed update, a race, or a bad migration can leave the stored balance disagreeing with the
transaction history, and nobody notices until reconciliation (or a customer) finds it.

Driftless removes the failure mode entirely: **there is no balance column.** A balance is *always*
recomputed by summing immutable journal entries. The history and the balance literally cannot
disagree, because they are the same query. This module owns that guarantee.

## Business logic

See [`../03-double-entry-ledger.md`](../03-double-entry-ledger.md) for the full worked example. The
essentials:

- A `post(PostingRequest)` writes one **balanced** transaction: ≥2 `PostingLine`s whose debits and
  credits net to **zero per currency**. Unbalanced ⇒ `BalanceInvariantViolation`, nothing written.
- Each `PostingLine(account, Direction, Money)` carries a **strictly positive** `Money`; the
  `Direction` (`DEBIT`/`CREDIT`) supplies the sign.
- `balanceOf(account)` returns a `Balance { posted, available }`. `posted` is the signed `SUM` over
  `journal_entry`; `available = posted − activeHoldTotal`, where the hold total comes from the
  `HoldView` SPI (see below).
- `post` is **idempotent**: `transaction.idempotency_key` is `UNIQUE`, so a replay returns the
  original `PostingResult` with `replayed=true` and writes no new rows.

## The frozen contract

```java
public interface Ledger {
    Account       openAccount(Account account);     // idempotent on a caller id
    PostingResult post(PostingRequest request);      // atomic · balanced · append-only · idempotent
    Balance       balanceOf(AccountId account);       // { posted, available } — derived by summation
    Transaction   findTransaction(TxId id);
    List<JournalEntry> entriesFor(AccountId account, Page page);
}
```

## The HoldView SPI — available vs posted

```java
public interface HoldView {                          // io.driftless.ledger.spi
    Money activeHoldTotal(AccountId account, Currency currency);
}
```

The ledger ships a **default no-op** `HoldView` (`available == posted`) as an auto-configuration
guarded by `@ConditionalOnMissingBean`. The **auth saga (Spec 03)** supplies the *real*
implementation — summing `ACTIVE` holds — which cleanly replaces the default. This is the seam that
lets a hold reduce **available** without moving **posted** money, defined at the Spec 00 freeze so
Spec 03 never has to reopen the contract.

## How the invariants are enforced here

| Invariant | Mechanism |
|-----------|-----------|
| **Balance** | `post()` groups lines by currency, sums debits/credits, throws `BalanceInvariantViolation` unless each nets to zero — writing nothing. |
| **Immutability** | No update/delete code path on `journal_entry`; Flyway `V2` installs a `BEFORE UPDATE OR DELETE` trigger that rejects mutation at the database. |
| **Idempotency** | `UNIQUE` index on `transaction.idempotency_key`; a concurrent duplicate collides at the DB and replays the original result. |

## Schema (Flyway `V1`/`V2`)

- `account` — `id, type, currency, name, created_at`. Created once; never mutated. **No balance
  column.**
- `transaction` — `id, idempotency_key (UNIQUE), occurred_at, posted_at, description`.
- `journal_entry` — `id, transaction_id, account_id, direction, amount_minor, currency,
  sequence_no`. Append-only; protected by the `V2` trigger.

## Tests

Integration tests run against real Postgres (Testcontainers): balanced/unbalanced posts, the
append-only trigger rejecting direct `UPDATE`/`DELETE`, idempotent replay, `HoldView`-driven
available balance, and a jqwik zero-drift property. This is the bedrock the Spec 07 gate builds on.
