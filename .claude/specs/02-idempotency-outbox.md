# Spec 02 — Idempotency & Outbox

| Field | Value |
|-------|-------|
| **Owns** | Idempotency keys, replay guard, transactional outbox for exactly-once events |
| **CV story** | FinBridge idempotency, hardened |
| **Depends on** | Spec 01 (frozen ledger interface) |
| **Blocks** | Spec 03 (auth saga consumes idempotency + outbox) |
| **Parallelizable** | Yes — parallel batch with 04 / 05 / 06 (runs against frozen ledger API) |

## North star

Money correctness under failure means a retried request must never double-charge, and an event
must be published exactly once even if the process dies after the DB commit. This module is the
reusable spine that gives every mutating operation those guarantees.

## The three invariants (this module owns invariant 3 system-wide)

1. **Balance invariant** — never write an unbalanced ledger transaction (inherited from Spec 01).
2. **Immutability** — idempotency records and outbox rows are append-only; status transitions are
   updates to status only, never deletion of history.
3. **Idempotency** — every mutating endpoint is safe to retry; a replay returns the original
   result, never a double-effect. **This module implements that guarantee.**

## Goal

Provide two reusable mechanisms used by all mutating flows:

1. **Idempotency keys + replay guard** — a request fingerprint store so a retried request returns
   the original response and produces no new side effects.
2. **Transactional outbox** — events are written in the *same DB transaction* as the state change,
   then relayed to consumers exactly once (at-least-once delivery + idempotent consumers =
   effectively-once).

## Public interface (consumed by Spec 03 and any mutating endpoint)

```java
package io.driftless.idempotency.api;

/** Wraps an operation so it runs once per key; replays return the stored result. */
public interface IdempotencyGuard {
    /**
     * @param key        caller-supplied Idempotency-Key (per endpoint scope)
     * @param requestHash hash of the canonical request body; a key reused with a DIFFERENT body
     *                    is a conflict (IdempotencyConflict), not a replay
     * @param operation  the side-effecting work; executed at most once per key
     */
    <T> IdempotentResult<T> execute(String key, String requestHash, Supplier<StoredResult<T>> operation);
}

public record IdempotentResult<T>(T value, boolean replayed) {}
```

```java
package io.driftless.outbox.api;

/** Append an event in the SAME transaction as the state change. */
public interface OutboxWriter {
    void append(OutboxEvent event);   // must be called within the caller's DB transaction
}

public record OutboxEvent(
    String aggregateType,   // e.g. "ledger.transaction", "auth.authorization"
    String aggregateId,
    String eventType,       // e.g. "AuthorizationCaptured"
    String payloadJson,
    Instant occurredAt) {}
```

## Data model

- **`idempotency_record`** — key (PK within scope), scope/endpoint, request_hash, response_blob,
  status (`IN_PROGRESS` / `COMPLETED`), created_at, completed_at. A second request with the same
  key while `IN_PROGRESS` must not run the operation twice (handle via row lock / unique
  constraint + retry).
- **`outbox_event`** — id, aggregate_type, aggregate_id, event_type, payload_json, occurred_at,
  status (`PENDING` / `PUBLISHED`), published_at, attempts. Append-only except the status column.

## Mechanics

- **Replay guard:** insert `idempotency_record` with the key inside the same transaction as the
  business write. Unique constraint on key catches concurrent duplicates; the loser reads and
  returns the stored result. Same key + different `request_hash` ⇒ `IdempotencyConflict` (409).
- **Outbox:** `OutboxWriter.append` writes to `outbox_event` in the caller's transaction — atomic
  with the state change. A **relay** (scheduled poller, e.g. every N ms, `FOR UPDATE SKIP LOCKED`)
  reads `PENDING` rows in order, publishes them, marks `PUBLISHED`. Delivery is at-least-once;
  consumers must be idempotent (use the event id). The relay is crash-safe: a process death after
  commit but before publish simply re-publishes on restart.
- Ordering: relay preserves per-aggregate ordering (order by `id`/`occurred_at`, partitioned by
  aggregate where needed).

## REST contract (the header convention for the whole system)

- Every mutating endpoint accepts `Idempotency-Key: <opaque>`.
- Missing key on a mutating endpoint ⇒ `400`.
- Replay (same key + same body) ⇒ the original `2xx` response, with a header marking it a replay.
- Same key + different body ⇒ `409 Conflict`.

## Acceptance criteria (falsifiable)

- [ ] Two concurrent requests with the same key produce **one** side effect; both callers receive
      the same response.
- [ ] A retried request after a simulated crash (process killed post-commit) returns the original
      result and creates no new ledger rows.
- [ ] Same key + different request body returns `409`.
- [ ] An event appended via `OutboxWriter` is committed atomically with the ledger write: if the
      ledger write rolls back, the outbox row is absent.
- [ ] Killing the relay between commit and publish results in the event being published exactly
      once after restart (no loss, no observable duplicate to an idempotent consumer).
- [ ] Relay preserves per-aggregate ordering.
- [ ] Integration tests use Testcontainers Postgres.

## Gate

Feeds the reconciliation-correctness gate: the fault-injection harness (Spec 07) will kill the
process at each step and assert no double-effect and no drift. Your mechanisms must survive that.

## Out of scope

- The auth saga itself (Spec 03) — you provide the guard + outbox it uses.
- A real message broker — in-process/DB-backed relay is sufficient for MVP; keep the
  `OutboxWriter`/relay seam clean so a broker can slot in later.
