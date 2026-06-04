# The Double-Entry Ledger — Business Logic & Worked Examples

This is the heart of Driftless. If you understand this document, you understand why the system can
*prove* it never loses or conjures money.

## 1. The one rule of double-entry

> **Every transaction touches at least two accounts, and the debits equal the credits.**

Money is never created or destroyed inside the ledger — it only **moves between accounts**. Because
every posting is balanced, the signed sum of *all* journal entries that ever existed is always
exactly **zero**. That single arithmetic fact is the whole zero-drift guarantee.

In Driftless:

- An amount is **`Money`** — an integer count of an ISO-4217 currency's **minor units** (cents,
  pence). No floating point ever touches a balance.
- Each leg of a transaction is a **`PostingLine(account, Direction, Money)`** where `Direction` is
  `DEBIT` or `CREDIT`. The `Money` amount is always **strictly positive**; the `Direction` carries
  the sign.
- A **`PostingRequest`** bundles ≥2 lines with an `idempotencyKey` and a business `occurredAt`. The
  ledger rejects it unless the debits and credits net to zero **per currency**.

```java
// The frozen ledger contract (io.driftless.ledger.api.Ledger)
Account     openAccount(Account account);
PostingResult post(PostingRequest request);   // atomic, balanced, append-only, idempotent
Balance     balanceOf(AccountId account);      // { posted, available } — derived by summation
Transaction findTransaction(TxId id);
List<JournalEntry> entriesFor(AccountId account, Page page);
```

## 2. Accounts: what the books are made of

`AccountType` is one of `ASSET`, `LIABILITY`, `EQUITY`, `REVENUE`, `EXPENSE`. For an issuer running
prepaid/debit cards, the two accounts that matter most are:

| Account | Type | Meaning |
|---------|------|---------|
| `cardholder_funds` | `LIABILITY` | Money the issuer **owes** the cardholder (their loaded balance). |
| `settlement` | `ASSET` | The issuer's settlement/funding asset that pays merchants. |

Normal-balance convention: a **LIABILITY** increases on the **CREDIT** side; an **ASSET** increases
on the **DEBIT** side. The signed-sum invariant treats DEBIT and CREDIT as opposite signs, so a
balanced transaction contributes **0** to the global sum.

## 3. Worked example: load, authorize, capture, reverse

Let's follow $100.00 (`amountMinor = 10_000`, USD) through the lifecycle. We track two numbers per
account:

- **posted** — settled balance, the `SUM` over `journal_entry`.
- **available** — `posted − activeHoldTotal`, where holds are reported by the `HoldView` SPI.

### Step 0 — Load $100 onto the card

The cardholder loads $100. The issuer now owes them $100 (liability up) and holds $100 in settlement
(asset up).

```
PostingRequest(key="load-1", occurredAt=…, lines=[
    PostingLine(settlement,        DEBIT,  $100.00),   // asset increases
    PostingLine(cardholder_funds,  CREDIT, $100.00),   // liability increases
])
```

| Account | posted | available |
|---------|-------:|----------:|
| cardholder_funds | **$100.00** | $100.00 |
| settlement | $100.00 | — |

Debits ($100) == Credits ($100) ⇒ this transaction adds **0** to the global signed sum. ✔

### Step 1 — Authorize a $30 purchase (place a HOLD)

The cardholder taps for $30. The rule engine approves. The saga places a **hold** — it does **not**
move posted money yet, it reduces **available**:

```
hold = { authorization_id, account=cardholder_funds, amount=$30.00, status=ACTIVE }
```

`HoldView.activeHoldTotal(cardholder_funds, USD)` now returns **$30.00**.

| Account | posted | available |
|---------|-------:|----------:|
| cardholder_funds | $100.00 | **$70.00** ← `100 − 30` |

Nothing was posted to `journal_entry`, so the global sum is still 0. The customer simply can't spend
the held $30 twice. **This available-vs-posted split is the real-world distinction most toy ledgers
get wrong.**

### Step 2a — Capture the $30 (settle the purchase)

The merchant captures. Now real money moves: the issuer pays the merchant from settlement, and the
cardholder's liability drops. The hold is **released**.

```
PostingRequest(key="cap-1", occurredAt=…, lines=[
    PostingLine(cardholder_funds, DEBIT,  $30.00),   // liability decreases
    PostingLine(settlement,       CREDIT, $30.00),   // asset decreases (paid out)
])
// then: hold status ACTIVE -> RELEASED
```

| Account | posted | available |
|---------|-------:|----------:|
| cardholder_funds | **$70.00** | $70.00 (hold released, posted already reflects it) |
| settlement | $70.00 | — |

Again debits == credits ⇒ contributes 0 to the global sum. The capture is a **new** transaction; the
load transaction from Step 0 is never touched. ✔

### Step 2b — *Instead*, reverse the authorization

Suppose the purchase is reversed (or the partner call timed out — see below). We **never delete** the
hold or edit history. We release the hold via a compensating action:

```
hold status ACTIVE -> RELEASED      // available restored to $100.00
// (if a capture had already posted, we post the INVERSE settlement as a new compensating transaction)
```

| Account | posted | available |
|---------|-------:|----------:|
| cardholder_funds | $100.00 | **$100.00** ← restored |

The original entries are immutable; the correction is additive. ✔

## 4. The bounded-timeout compensating reversal (the "$20K fix")

The dangerous case is Step 1 → partner call → **no answer**. The hold is placed, then the external
authorization call **times out or 5xx's**. A naive system leaves the $30 hold dangling forever — a
**phantom hold**.

Driftless instead runs the partner call under a **hard bounded timeout**. On timeout/failure it
triggers a **compensating reversal** that releases the hold:

```
place hold ($30) ──▶ partner.authorize() ──(timeout)──▶ COMPENSATING ──▶ release hold ──▶ REVERSED
                                                          (retried until it succeeds)
```

Because **both** the partner endpoint and the saga are **idempotent**, a *late* partner success
arriving *after* compensation is absorbed safely — it either no-ops or is itself reversed. The result:
**zero drift** even though a remote system failed mid-flight. This is the exact class of bug that
costs issuers real money; Driftless makes it structurally impossible to leave money in limbo.

## 5. Why balance can never silently drift

There is **deliberately no `balance` column** anywhere in the schema. Posted balance is *always*
recomputed:

```sql
-- posted balance of an account = signed sum over its immutable entries
SELECT SUM(CASE WHEN direction = 'DEBIT'  THEN  amount_minor
                WHEN direction = 'CREDIT' THEN -amount_minor END)
FROM journal_entry WHERE account_id = :account;   -- (sign convention per account type)
```

Consequences:

- The books **are** their own source of truth. A cached balance can't drift from history, because
  there is no cached balance.
- `journal_entry` is **append-only**, enforced both in code (no update/delete path exists) and by a
  database **trigger** that rejects any `UPDATE`/`DELETE`. Corrections are new compensating entries.
- The global proof is a one-line summation: `SUM(signed amount) over ALL entries == 0`. The
  reconciliation job (Spec 07) runs exactly this, continuously, and the property test asserts it
  after *random* `auth/capture/reverse/fault` sequences.

## 6. How the invariants show up in the code

| Invariant | Enforcement |
|-----------|-------------|
| **Balance** | `post()` sums each currency's debits and credits and throws `BalanceInvariantViolation` (writing nothing) unless they net to zero. |
| **Immutability** | No update/delete code path on `journal_entry`; a Flyway `V2` trigger rejects `UPDATE`/`DELETE` at the database. Reversals are new `post()` calls. |
| **Idempotency** | `post()` is keyed by `idempotency_key` (UNIQUE index); a replay returns the original `PostingResult` with `replayed=true` and writes no new rows. Saga steps wrap this in the `IdempotencyGuard`. |

The next document, [`04-invariants.md`](./04-invariants.md), drills into each of these with the
specific classes and tests that hold the line.
