# Module: `auth` — The Authorization Lifecycle Saga (Spec 03)

> The engine that **keeps money correct under failure.** It runs the full
> authorize → capture → reversal lifecycle, maintains the real distinction between **available** and
> **posted** balance, and — the crux — when the external partner leg times out, it runs a
> **bounded-timeout compensating reversal** so a stuck call never leaves a phantom hold. This is the
> literal "$20K drift fix" story.

## Why this module exists

The dangerous moment in card processing is the external authorization call. You've placed a hold on
the customer's funds; now you call the network; and it **doesn't answer**. Did it authorize? Did it
not? If you do nothing, the hold dangles forever (a phantom hold freezing the customer's money). If
you guess wrong, you drift. Multiply by millions of transactions and you have the kind of incident
that makes the news.

This module makes that moment safe. Every step is balanced, idempotent, and crash-recoverable, and
the partner call runs under a hard timeout with automatic compensation. The result is the property
the whole project is built to prove: **zero drift, even when the partner fails mid-flight.**

## The state machine

```
   authorize request
        │
        ▼
   [evaluate rules] ──DECLINE──▶ DECLINED        (no hold, no money moved)
        │ APPROVE
        ▼
   [place HOLD]   available -= amount  (posted unchanged)   ── persisted as AUTHORIZING ──┐
        │                                                                                  │ commit
        ▼                                                                                  ▼
   [call partner.authorize] ── bounded timeout ──┐                              (durable before HTTP)
        │ approve                                │ timeout / 5xx / no-response
        ▼                                        ▼
   AUTHORIZED                            COMPENSATING ── release hold ──▶ REVERSED  (zero drift)
        │
        ├── capture ──▶ [post settlement] posted -= amount, hold released ──▶ CAPTURED
        └── reverse ──▶ [compensating reversal] release hold (+ inverse settlement if captured) ──▶ REVERSED
```

Terminal: `DECLINED`, `CAPTURED`, `REVERSED`. In-flight: `AUTHORIZING`, `COMPENSATING`.

## Available vs posted — the core distinction

- **Hold (authorization):** an `ACTIVE` row in the `hold` table. `HoldViewImpl` sums active holds per
  `(account, currency)`, so the ledger computes `available = posted − activeHoldTotal`. Posted money
  does **not** move. This is the real-world "your balance shows less but the charge hasn't settled."
- **Capture:** posts a **balanced** settlement to the ledger (posted moves) and flips the hold to
  `RELEASED`.
- **Reversal:** releases the hold (available restored). If a capture had already posted, it posts the
  **inverse settlement as a new compensating transaction** — the original entries are never touched.

`HoldViewImpl` is the real `HoldView` bean; it cleanly replaces the ledger's default no-op (which was
`@ConditionalOnMissingBean` precisely so this module could supply the real one without reopening the
frozen contract).

## The bounded-timeout compensating reversal (the "$20K fix")

The `authorize` operation is deliberately **two-phase**, and this is the heart of crash-safety:

1. **Phase 1 (one transaction, inside the idempotency guard):** evaluate rules → place the hold →
   persist the authorization as `AUTHORIZING` → record velocity → **commit.** The hold and
   authorization are now **durable before any network call is made** — a DB transaction is never held
   open across the HTTP call.
2. **The partner call** runs over real HTTP under a **hard bounded timeout** (connect + read timeouts
   from `auth.partner.*` config; defaults 500ms/800ms).
3. **Phase 2 (a separate transaction):**
   - partner approves → `AuthorizationFinalizer` writes `AUTHORIZED` + `partner_ref`, emits the event;
   - partner declines → release hold → `DECLINED`;
   - **timeout / 5xx / no-response → `CompensationRunner`** drives `COMPENSATING → REVERSED`, releasing
     the hold. Because the partner is idempotent on a stable `requestId = authId`, compensation can
     also discover a hidden `partner_ref` (via `GET /partner/state/{requestId}`) and send an
     idempotent `partner.reverse`, so a **late** partner success is neutralized. Compensation is
     **retried** (bounded) and any exhausted attempt leaves the row recoverable — it can never
     silently drop.

### Crash recovery

`RecoverySweep` (`@Scheduled` + on-demand) scans for in-flight rows (`AUTHORIZING`/`COMPENSATING`)
older than `auth.recovery.stuck-after`. For `AUTHORIZING` it consults `GET /partner/state` to decide
(approved → finalize; declined → release + decline; unknown/side-effect-only → compensate); for
`COMPENSATING` it re-drives the idempotent compensation. A partial index on
`(status, updated_at) WHERE status IN ('AUTHORIZING','COMPENSATING')` keeps the scan cheap as
terminal rows accumulate. This is what makes "process killed at any step boundary recovers cleanly"
true — and Spec 07 kills the process to prove it.

## The account model

A cardholder funding account plus a deterministic per-currency `settlement` clearing account. A
**capture** posts `CREDIT cardholder / DEBIT settlement` (posted drops, hold released); reversing a
capture posts the **inverse as a new transaction** (`DEBIT cardholder / CREDIT settlement`). A pure
authorization reversal just releases the hold — no posted money moved. Every post nets to zero per
currency or the ledger rejects it.

## Public REST API

```
POST /authorizations            { account, amount, mcc, merchantId, tokenId? }  -> { authId, status }
POST /authorizations/{id}/capture   { amount? }                                 -> { status }
POST /authorizations/{id}/reverse                                              -> { status }
GET  /authorizations/{id}                                                       -> full state
GET  /accounts/{id}/balance                                                    -> { posted, available }
```

Every mutating endpoint reads `Idempotency-Key` (missing → 400, same key + different body → 409,
replay → original 2xx). The controller is thin; domain exceptions map via `@RestControllerAdvice`;
DTOs in/out (never JPA entities). When a `tokenId` is supplied, authorization is gated on token
`status == ACTIVE`.

## How the invariants are upheld

| Invariant | In the saga |
|-----------|-------------|
| **Balance** | every capture/reversal is a balanced 2-leg `ledger.post`; a hold moves no posted money. After any auth/capture/reverse/timeout sequence the global signed journal sum stays 0. |
| **Immutability** | reversals are **new** compensating posts; holds are `RELEASED` by status flip, never deleted; a capture-reversal adds one new entry, original untouched. |
| **Idempotency** | every step wrapped in `IdempotencyGuard` (per-endpoint key namespaces); ledger posts carry stable keys; replays return the original result with no extra rows; same key + different body → 409. |

## Data model

- **`authorization`** (quoted — it's a Postgres reserved word) — saga aggregate; only
  `status/partner_ref/decline_reason/updated_at` mutable; `version` for optimistic locking on top of
  the `SELECT … FOR UPDATE` the saga loads under.
- **`hold`** — the available-vs-posted seam; `ACTIVE` rows reduce available; released by status flip,
  never deleted.

## Tests

`AuthSagaIT`, `AuthControllerIT`, `AuthEdgeCasesIT`, `RecoverySweepIT` (41 tests) against
Testcontainers Postgres with a controllable in-test partner stub that can approve, decline, hang past
the timeout, fail-before-response, and deliver a late success — covering every acceptance criterion,
including timeout → compensation → zero drift and late-response absorption.
