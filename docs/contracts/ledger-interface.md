# Frozen contract — Ledger public interface

- Status: **FROZEN** as of the Spec 00/01 gate
- Package: `io.driftless.ledger.api` (+ `io.driftless.ledger.spi`)
- Module: `ledger` (depends only on `common`)
- Authored by: Spec 01 · Published & frozen by: Spec 00

## Why this is frozen

Downstream modules (`idempotency`, `auth`, `rules`, `tokens`, `recon`) are generated and built in
parallel once Spec 01's interfaces are frozen. They code against these shapes, so the shapes must
not move underneath them. **Do not change any type below — interface, record, enum, or exception —
without re-opening the Spec 00/01 freeze gate.** The package is also marked frozen in its
`package-info.java`.

"Frozen" means the public **shape** (type names, method signatures, record components, enum
constants) is stable. Spec 01 supplies the implementation behind `Ledger`; that is not part of the
frozen surface.

## Surface

### Enums

```java
public enum Direction { DEBIT, CREDIT }

public enum AccountType { ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE }
```

### Records (`io.driftless.ledger.api`)

| Record | Components |
|---|---|
| `Account` | `AccountId id, AccountType type, Currency currency, String name` |
| `PostingLine` | `AccountId account, Direction direction, Money amount` |
| `PostingRequest` | `String idempotencyKey, Instant occurredAt, String description, List<PostingLine> lines` |
| `PostingResult` | `TxId transactionId, Instant postedAt, boolean replayed` |
| `Balance` | `AccountId account, Currency currency, Money posted, Money available` |
| `JournalEntry` | `EntryId id, TxId transactionId, AccountId account, Direction direction, Money amount, int sequenceNo` |
| `Transaction` | `TxId id, String idempotencyKey, Instant occurredAt, Instant postedAt, String description, List<JournalEntry> entries` |
| `Page` | `int number, int size` |

`Money` and the typed ids (`AccountId`, `TxId`, `EntryId`) come from the `common` kernel;
`Currency` is `java.util.Currency`; `Instant` is `java.time.Instant`.

### Interface (`io.driftless.ledger.api.Ledger`)

```java
Account       openAccount(Account account);
PostingResult post(PostingRequest request);
Balance       balanceOf(AccountId account);
Transaction   findTransaction(TxId id);
List<JournalEntry> entriesFor(AccountId account, Page page);
```

Contract semantics (enforced by Spec 01's implementation):

- `post` rejects an unbalanced request with `BalanceInvariantViolation` and writes nothing.
- `post` rejects a `PostingLine` whose `Money.currency` differs from its account's currency.
- `post` is append-only (no `UPDATE`/`DELETE`) and idempotent on `idempotencyKey` — a replay
  returns the original `PostingResult` with `replayed == true` and creates no new rows.
- `balanceOf` is computed by summation over entries; `available == posted` when there are no holds,
  and `available == posted - activeHoldTotal` otherwise.

### Exception (`io.driftless.ledger.api`)

```java
public final class BalanceInvariantViolation extends io.driftless.common.error.DomainException { ... }
```

### SPI (`io.driftless.ledger.spi`)

```java
public interface HoldView {
    Money activeHoldTotal(AccountId account, Currency currency);
}
```

Spec 03 (auth saga) owns the hold lifecycle and feeds this view so the ledger can compute the
`available` balance. It is published at the freeze so Spec 03 need not re-open the gate.

## Change process

Any change to the surface above requires re-opening the Spec 00/01 gate, a review by the
reconciliation-correctness owner, and a coordinated update of every downstream consumer. See
[ADR-0005](../adr/ADR-0005-frozen-ledger-interface.md).
