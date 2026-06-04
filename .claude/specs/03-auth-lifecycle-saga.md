# Spec 03 — Auth Lifecycle Saga

| Field | Value |
|-------|-------|
| **Owns** | auth/hold → capture → reversal; available vs posted; bounded timeout + compensating reversal |
| **CV story** | BHN $20K drift fix |
| **Depends on** | Spec 01 (ledger), Spec 02 (idempotency + outbox), Spec 04 (partner simulator), Spec 05 (rule engine) |
| **Blocks** | Spec 07 (reconciliation/fault injection targets this flow) |
| **Parallelizable** | No — runs after the parallel batch (02/04/05/06) lands |

## North star

This is the engine that "keeps money correct under failure." It runs the full
**auth → capture → reversal** lifecycle, maintains the real distinction between **available** and
**posted** balance, and — the crux — when the external partner leg times out or fails, it runs a
**bounded-timeout compensating reversal** so a stuck downstream call never leaves a phantom hold or
a captured-but-unconfirmed charge. This is the literal "$20K drift fix" story.

## The three invariants (this saga is where they get tested hardest)

1. **Balance invariant** — every step posts a balanced ledger transaction (via Spec 01). A hold,
   a capture, and a reversal each net to zero across accounts.
2. **Immutability** — a reversal is a **new compensating** ledger transaction, never an edit of the
   original. Holds are released by posting their reversal, not by deleting them.
3. **Idempotency** — every saga step is wrapped in the Spec 02 guard. Retried authorize/capture/
   reverse never double-applies. A late/duplicate partner response is absorbed safely.

## Goal

Implement the authorization saga as a state machine that coordinates: rule evaluation (Spec 05),
hold placement (Spec 01 ledger + `HoldView`), the external partner call (Spec 04) under a
**bounded timeout**, capture, and compensating reversal on timeout/failure — all idempotent and
crash-safe (Spec 02).

## Lifecycle state machine

```
   authorize request
        │
        ▼
   [evaluate rules] ──DECLINE──▶ DECLINED (no hold, no money moved)
        │ APPROVE
        ▼
   [place HOLD]  ── available -= amount (posted unchanged)
        │
        ▼
   [call partner.authorize] ──(bounded timeout)
        │                   │
        │ approve           │ timeout / fail / no response
        ▼                   ▼
   AUTHORIZED          [COMPENSATING REVERSAL] ── release hold ──▶ REVERSED
        │
        ├── capture ──▶ [post settlement] posted += amount, hold released ──▶ CAPTURED
        │
        └── reverse ──▶ [compensating reversal] release hold ──▶ REVERSED
```

States: `DECLINED`, `AUTHORIZED`, `CAPTURED`, `REVERSED`, plus the transient `COMPENSATING`.
Terminal: `DECLINED`, `CAPTURED`, `REVERSED`.

## Available vs posted (the core distinction)

- **Hold (authorization):** reduces **available** balance immediately; **posted** is unchanged.
  Modeled as an active hold that the ledger's `HoldView` (Spec 01 SPI) reports, so
  `available = posted - activeHoldTotal`.
- **Capture:** converts a hold into a settled posting — **posted** decreases (or the liability
  moves), the hold is released. Net effect on available is already reflected.
- **Reversal:** releases the hold (available restored) via a compensating entry; if a capture had
  occurred, the reversal posts the inverse settlement. Original entries are never touched.

## Bounded-timeout compensating reversal (the $20K fix)

- The partner call (Spec 04) runs under a **hard, bounded timeout**.
- On timeout / connection drop / 5xx / no-response, the saga does **not** leave the hold dangling:
  it triggers a **compensating reversal** that releases the hold. Because the partner endpoint is
  idempotent (Spec 04) and the saga is idempotent (Spec 02), a *late* partner success arriving
  after compensation is absorbed without creating drift (it either no-ops or is itself reversed).
- The compensation path itself is retried until it succeeds (it's idempotent), so a failure to
  compensate cannot silently drop.

## Public interface (REST, all mutating endpoints take `Idempotency-Key`)

```
POST /authorizations        { account, amount, mcc, merchantId, tokenId? }  -> { authId, status }
POST /authorizations/{id}/capture   { amount? }                              -> { status }
POST /authorizations/{id}/reverse                                           -> { status }
GET  /authorizations/{id}                                                    -> full state
GET  /accounts/{id}/balance                                                 -> { posted, available }
```

## Data model

- **`authorization`** — id, account_id, amount, currency, mcc, merchant_id, token_id, status,
  partner_ref, created_at, updated_at, idempotency_key.
- **`hold`** — id, authorization_id, account_id, amount, currency, status (`ACTIVE`/`RELEASED`),
  placed_at, released_at. Feeds the ledger `HoldView`.
- Every state change writes a balanced ledger transaction (Spec 01) and an outbox event (Spec 02)
  in the same DB transaction.

## Integration points

- **Rules (05):** call `RuleEngine.evaluate` before placing a hold; `DECLINE` short-circuits.
- **Ledger (01):** place hold (affects available), post capture/reversal (affects posted); implement
  the `HoldView` SPI so `balanceOf` computes `available` correctly.
- **Idempotency/Outbox (02):** wrap every step; emit lifecycle events.
- **Partner (04):** call its endpoints under bounded timeout; rely on its idempotency for retries.
- **Tokens (06):** optionally gate authorization on token `status == ACTIVE`.

## Acceptance criteria (falsifiable)

- [ ] A successful authorize places a hold: **available decreases, posted unchanged**.
- [ ] Capture converts the hold to a settled posting; balances reflect it; the hold is released.
- [ ] Reverse releases the hold via a compensating entry; **the original entries are unchanged**.
- [ ] A `DECLINE` from the rule engine moves no money and places no hold.
- [ ] **Partner timeout triggers a compensating reversal**; afterward the hold is released and the
      account shows **zero drift** (sum of journal entries still 0).
- [ ] **`fail-before-response`** from the simulator (partner did the work, caller didn't hear back)
      results in compensation; a subsequent **late partner success is absorbed** with no drift and
      no double-effect.
- [ ] Every saga step is idempotent: replaying authorize/capture/reverse with the same key yields
      the original result and no extra ledger rows.
- [ ] Process killed at every step boundary leaves a consistent state on restart (no dangling hold,
      no half-posted capture) — verified with Spec 07's harness.
- [ ] The compensating-reversal path is itself retried to completion (cannot silently fail).

## Gate

This is the flow the **reconciliation-correctness agent** and the **property-based invariant test**
target: for any random sequence of `auth/capture/reversal/fault`, sum of journal entries `== 0`.
Spec 03 must be green under that test before the milestone is considered done.

## Out of scope

- The fault-injection harness and the property test runner (Spec 07 builds them; you must be
  testable by them).
- Settlement/clearing batch files — capture posts directly to the ledger for MVP.
