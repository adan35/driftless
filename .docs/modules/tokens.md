# Module: `tokens` — Tokenization Lifecycle (Spec 06)

## Why this module exists

A real issuer never lets a merchant or wallet touch the raw card number (PAN). Instead it issues
**tokens** — wallet/device surrogates (think Apple Pay / Google Pay, à la Visa VTS) that map to the
underlying card but have **their own lifecycle**. A card can stay valid while a specific device token
is suspended (phone lost), resumed (phone found), or permanently deactivated (device wiped).

Two things make this non-trivial and worth a dedicated module:

1. **The lifecycle is a strict state machine.** Illegal transitions (resume an already-active token,
   do anything to a deactivated one) must be impossible — not "discouraged".
2. **Status must propagate reliably.** Downstream consumers — including the authorization path —
   must always see a consistent token state. Driftless does this with the **transactional outbox**,
   so a status change and the event announcing it commit atomically.

Tokens move no money, so the *balance* invariant doesn't apply to balances here. But the other two
invariants do, sharpened: the **status history is append-only** (an audit trail, never edited), and
every transition is **idempotent**.

## The state machine

```
            create
              │
              ▼
         ┌─────────┐  activate   ┌──────────┐
         │ INACTIVE│────────────▶│  ACTIVE  │
         └────┬────┘             └────┬─────┘
              │                  ▲    │ suspend
              │ deactivate       │    ▼
              │            resume │  ┌──────────┐
              │                   └──│SUSPENDED │
              │                      └────┬─────┘
              ▼                           │ deactivate
         ┌───────────┐ ◀─────────────────┘
         │DEACTIVATED│   (terminal — no exit)
         └───────────┘
```

The whole machine is encoded **declaratively** in the `TokenTransition` enum: each transition knows
its `target()` state and the set of states it `isLegalFrom(...)`. The service is a thin enforcement
of those edges, so the rules live in one auditable place:

```java
ACTIVATE  (TokenStatus.ACTIVE,    {INACTIVE});
SUSPEND   (TokenStatus.SUSPENDED, {ACTIVE});
RESUME    (TokenStatus.ACTIVE,    {SUSPENDED});
DEACTIVATE(TokenStatus.DEACTIVATED, {INACTIVE, ACTIVE, SUSPENDED});  // terminal
```

Any edge not in that table throws a typed `IllegalTokenTransition` and **changes nothing**: no status
update, no history row, no outbox event.

## Data model

- **`token`** — `id, card_ref, status, created_at, updated_at`. `card_ref` is a **log-safe
  reference** to the card, never the raw PAN. Only `status`/`updated_at` are mutable.
- **`token_status_history`** — `id, token_id, from_status, to_status, transition, changed_at`.
  **Append-only**, one row per successful transition (the initial `CREATE` has `from_status = NULL`).
  Immutability is enforced defence-in-depth by a `BEFORE UPDATE OR DELETE` trigger that mirrors the
  ledger's `journal_entry` trigger — even a hand-written SQL `UPDATE`/`DELETE` is rejected.

## Public contract

```java
public interface TokenService {
    Token create(CreateTokenCommand cmd);   // -> INACTIVE
    Token activate(TokenId id);
    Token suspend(TokenId id);
    Token resume(TokenId id);
    Token deactivate(TokenId id);
    Token find(TokenId id);
}
```

Every transition emits a status-change event for downstream propagation:

```
TokenStatusChanged { tokenId, from, to, occurredAt }
```

## How a transition works (the atomic unit)

A successful transition does three things **in one database transaction**:

1. Update the `token` row's `status` + `updated_at` (from the injected `Clock`).
2. Append exactly **one** `token_status_history` row.
3. Append exactly **one** `TokenStatusChanged` event via `OutboxWriter.append(...)`.

The whole unit runs inside the supplier handed to `IdempotencyGuard.execute(...)`, which the guard
runs in a single transaction together with recording the idempotency key. `OutboxWriter.append` is
declared `Propagation.MANDATORY`, so it *must* join that transaction — guaranteeing the status
change, the audit row, and the event **commit or roll back as one**. A replay runs the supplier zero
times, so no duplicate row or event can appear.

## Idempotency: two surfaces over one engine

This is the subtle, important design decision.

- **Keyless surface (`TokenService`)** — used by internal callers such as the auth saga. A
  transition is treated as an idempotent **no-op replay only when the *same* transition was the one
  that last advanced the token** (the token's `last_transition` equals the requested transition *and*
  the token is already in that transition's target state). So a retried `activate`/`resume` replays,
  but a *fresh* `resume` of an `ACTIVE` token last advanced by `activate` is correctly **rejected**
  with `IllegalTokenTransition` — the keyless public contract matches the spec's "every illegal
  transition is rejected" exactly. The derived idempotency key folds in the per-token transition
  count (`tokens:<id>:<TRANSITION>:<n>`) so a legitimate *second* occurrence of a transition is a new
  operation, not a false replay.

  **Concurrency safety.** Because two *different* transitions on the same token take different
  idempotency keys (and so are not serialized by the guard alone), the guarded supplier loads the
  token **`FOR UPDATE`** (a pessimistic row lock, plus a `@Version` column for defence-in-depth)
  before re-checking the edge. A concurrent `deactivate` + `resume` on a `SUSPENDED` token can
  therefore never both commit: the loser blocks, re-reads the committed `DEACTIVATED` state, and is
  rejected — a terminal token can never be resurrected, and exactly one history row + one event is
  written.

- **Keyed surface (web)** — used by REST clients. Idempotency is anchored on the caller's
  `Idempotency-Key` header. The legality check runs **inside** the guarded operation, so:
  - a **retry under the same key** replays the original `2xx`;
  - a **fresh** `resume` of an already-`ACTIVE` token (new key) is a `422 IllegalTokenTransition`
    (the spec's illegal example);
  - missing key ⇒ `400`; same key + different body ⇒ `409`.

  Web/keyed calls are stored under a reserved guard namespace (`tokens.web:<callerKey>`), separate
  from the internal `tokens:` derived keys, so a client can never craft a key that aliases — and thus
  silently replays — an internal transition.

`create` is idempotent on a caller-supplied `TokenId`; its request hash excludes the randomly-minted
id so a keyed retry replays the original token rather than conflicting.

## Worked example

```
create(card_ref="tok_ref_abc")                 → Token{status=INACTIVE}     + history[CREATE]      + event
activate(id)                                   → Token{status=ACTIVE}       + history[ACTIVATE]    + event
suspend(id)   // phone lost                     → Token{status=SUSPENDED}    + history[SUSPEND]     + event
resume(id)    // phone found                    → Token{status=ACTIVE}       + history[RESUME]      + event
resume(id)    // again, new key — ILLEGAL        → 422 IllegalTokenTransition (nothing written)
deactivate(id)// device wiped                    → Token{status=DEACTIVATED} + history[DEACTIVATE]  + event
activate(id)  // out of terminal — ILLEGAL        → 422 IllegalTokenTransition (nothing written)
```

At the end, `token_status_history` holds exactly 5 immutable rows (CREATE, ACTIVATE, SUSPEND, RESUME,
DEACTIVATE) and the outbox carries 5 `TokenStatusChanged` events — a perfect, tamper-evident audit
trail that downstream systems (including the auth saga's `status == ACTIVE` gate) can rely on.

## PAN safety

Only `card_ref` (a reference/hash) is stored; logs print **token id + transition only**. There is no
`pan` column anywhere, and a test asserts it. Real PAN encryption/HSM/PCI handling is explicitly out
of scope (future work) — this module proves the *lifecycle* correctly without ever holding sensitive
card data.

## Tests

- `TokenLifecycleServiceIT` — state machine (legal + illegal), history-row count, outbox emission,
  idempotency (keyless replay-on-state and the second-transition boundary), PAN safety, append-only
  trigger.
- `TokenControllerIT` — REST surface: `Idempotency-Key` handling (missing/replay/conflict), illegal
  transition → 422, DTO mapping (never returns a JPA entity).

Run: `./mvnw -B -pl tokens -am verify` (Testcontainers Postgres).
