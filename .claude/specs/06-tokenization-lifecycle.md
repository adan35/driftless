# Spec 06 — Tokenization Lifecycle

| Field | Value |
|-------|-------|
| **Owns** | create / activate / suspend / resume / deactivate + status propagation |
| **CV story** | Wallet Token Activation (VTS) |
| **Depends on** | Spec 00 (foundation); Spec 02 (idempotency + outbox for status propagation) |
| **Blocks** | — (auth saga may check token status, but tokens are largely independent) |
| **Parallelizable** | Yes — parallel batch with 02 / 04 / 05 |

## North star

A real issuer maps a payment card (PAN) to one or more tokens (wallet/device tokens, à la VTS).
The token has its own lifecycle independent of the card, and its status must **propagate**
reliably (via the outbox) so downstream consumers — including the auth path — always see a
consistent token state. This spec owns that lifecycle as a clean state machine.

## The three invariants (how they apply here)

Tokens move no money, so the balance/immutability invariants don't apply to balances here — but
the **status history is append-only** (an audit trail of transitions, never edited), and every
mutating token operation is **idempotent** (Spec 02 guard). Status changes propagate via the
**outbox** (Spec 02) for exactly-once delivery.

## Goal

A tokenization service that maps a card to tokens and manages each token through a strict
lifecycle state machine, emitting status-change events for downstream propagation.

## State machine

```
            create
              │
              ▼
         ┌─────────┐  activate   ┌──────────┐
         │ INACTIVE│────────────▶│  ACTIVE  │
         └─────────┘             └────┬─────┘
              ▲                       │  suspend
              │                       ▼
              │  (deactivate)    ┌──────────┐
              │◀─────────────────│SUSPENDED │
              │      resume ─────┘──────────┘
              ▼
         ┌──────────┐   (terminal)
         │DEACTIVATED│
         └──────────┘
```

- **create** → `INACTIVE`
- **activate**: `INACTIVE` → `ACTIVE`
- **suspend**: `ACTIVE` → `SUSPENDED`
- **resume**: `SUSPENDED` → `ACTIVE`
- **deactivate**: `ACTIVE`/`SUSPENDED`/`INACTIVE` → `DEACTIVATED` (terminal; no exit)
- Any other transition is rejected with a typed `IllegalTokenTransition`.

## Public interface

```java
package io.driftless.tokens.api;

public enum TokenStatus { INACTIVE, ACTIVE, SUSPENDED, DEACTIVATED }

public record Token(
    TokenId id,
    String pan,          // reference to the underlying card (store a reference/hash, not raw PAN in logs)
    TokenStatus status,
    Instant updatedAt) {}

public interface TokenService {
    Token create(CreateTokenCommand cmd);     // -> INACTIVE
    Token activate(TokenId id);
    Token suspend(TokenId id);
    Token resume(TokenId id);
    Token deactivate(TokenId id);
    Token find(TokenId id);
}
```

Each mutating method goes through the Spec 02 `IdempotencyGuard` and emits a status-change event
via the Spec 02 `OutboxWriter`:

```
TokenStatusChanged { tokenId, from, to, occurredAt }
```

## Data model

- **`token`** — id, card_ref, status, created_at, updated_at.
- **`token_status_history`** — id, token_id, from_status, to_status, changed_at. Append-only.

## Status propagation

- On every successful transition, append a `TokenStatusChanged` outbox event (atomic with the
  status write). Downstream consumers (e.g. the auth path checking whether a token may transact)
  read the propagated status.
- Optionally expose a read endpoint so the auth saga can gate authorizations on `status == ACTIVE`
  (coordinate with Spec 03; keep it a status read, not a hard coupling).

## Acceptance criteria (falsifiable)

- [ ] `create` yields a token in `INACTIVE`.
- [ ] Each legal transition succeeds and lands in the correct target state.
- [ ] Every illegal transition (e.g. `resume` from `ACTIVE`, anything out of `DEACTIVATED`) is
      rejected with `IllegalTokenTransition` and changes nothing.
- [ ] Each transition appends exactly one `token_status_history` row.
- [ ] Each transition emits exactly one `TokenStatusChanged` outbox event, atomic with the write.
- [ ] Mutating operations are idempotent: a retried `activate` returns the same result and emits no
      duplicate event/history row.
- [ ] Raw PAN never appears in logs.

## Gate

QA + security agents verify the state machine has no illegal transitions and that PAN handling is
safe. Outbox propagation is exercised by the fault-injection harness (Spec 07).

## Out of scope

- Real PAN encryption/HSM/PCI scope — store a reference/hash and document that real PCI handling is
  future work. Do not implement raw card-number storage.
- Provisioning to a real wallet (VTS/MDES) — simulate the propagation via the outbox.
