# Module: `idempotency` (+ `outbox`) — Exactly-Once Under Retry (Spec 02)

> Two cross-cutting guarantees the whole system leans on: **a mutating operation runs at most once
> per key**, and **an event is published exactly once, atomically with the state change that
> produced it.**

## Why this module exists

Networks retry. Clients retry. Crashes force retries. In a money system, an unguarded retry is a
double charge or a double release. And announcing a state change to the outside world is its own
trap: if you write to the database and *then* publish an event, a crash in between either loses the
event (state changed, nobody told) or, if you publish first, announces something that never
committed. Both are drift.

This module solves both with one idea: **put the guarantee in the same database transaction as the
business write.**

## Part 1 — The idempotency guard

```java
public interface IdempotencyGuard {                    // io.driftless.idempotency.api
    <T> IdempotentResult<T> execute(String key, String requestHash, Supplier<StoredResult<T>> operation);
}
```

- The operation runs **at most once** per `key`. Its result is serialized into the idempotency
  record's `response_blob` **in the same transaction** as the operation's own writes — so the side
  effect and the "I did this" record commit or roll back together.
- A concurrent duplicate is caught by a **unique constraint**; the loser reads and returns the
  winner's stored result.
- A key reused with a **different** `requestHash` is an `IdempotencyConflict` (HTTP 409) — never a
  silent replay of a different request.
- After a crash, a committed prior result replays **without** re-running the side effect (the value
  is rebuilt from the stored response and its captured type).

This is the system-wide implementation of **invariant 3**. The ledger, tokens, and the auth saga all
wrap their mutating operations in it.

## Part 2 — The transactional outbox

```java
public interface OutboxWriter {                        // io.driftless.outbox.api
    void append(OutboxEvent event);   // MUST be called inside the caller's transaction
}
```

The pattern:

1. In your business transaction, do the state change **and** `outboxWriter.append(event)`. The event
   row is inserted as `PENDING` in the *same* transaction. If the business write rolls back, the
   event vanishes with it; if it commits, the event is guaranteed to exist.
2. A separate **relay** (`@Scheduled`) polls `PENDING` events in insertion order and publishes them,
   marking them delivered. Consumers are idempotent (keyed by the event's persisted id), so
   at-least-once delivery + idempotent consumer = **effectively once**.

`append` is declared `Propagation.MANDATORY`, so it *must* join an existing transaction — you
physically cannot append an event outside a business transaction, which makes the atomicity
guarantee structural rather than a convention you have to remember.

## Worked example (how tokens uses it)

A token `activate` does three writes — update `token.status`, append a `token_status_history` row,
and `OutboxWriter.append(TokenStatusChanged)` — all inside the supplier handed to
`IdempotencyGuard.execute`. The guard runs that supplier in one transaction together with recording
the idempotency key. Result: a retried `activate` replays the original result and emits **no**
duplicate event or history row, and the event can never exist without the status change (or vice
versa).

## Why it matters for zero drift

```
Idempotency guard  → retries converge to exactly one effect (no double charge / double release).
Transactional outbox → every committed state change is announced exactly once, and nothing is
                       announced that didn't commit.
                              │
                              ▼
   Failures and recoveries don't add phantom money, and downstream views stay consistent
   with the ledger — the preconditions the reconciliation proof (Spec 07) relies on.
```

## Tests

Testcontainers Postgres: concurrency races on one key (one effect, one stored result), replay
returns the original with no new rows, same key + different body ⇒ conflict, outbox atomicity (a
rolled-back business write leaves no event), and the relay publishing `PENDING` events in order.
