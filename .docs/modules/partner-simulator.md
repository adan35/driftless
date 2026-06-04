# Module: `partner-simulator` — A Real Failure Boundary (Spec 04)

> The one genuinely **separate service**. It exists so the auth saga has a *real network leg* to fail
> against — because drift bugs hide in the seams between processes, not inside a single transaction.

## Why this module exists

You cannot prove "money stays correct when a downstream call fails" if there is no downstream call
that can actually fail. A monolith calling itself in-process can't time out the way a remote
authorizer can. So Driftless ships exactly one separately-deployable service — its own Spring Boot
app, its own container, reached only over HTTP — that stands in for the external authorization
network. Crucially, it has a **fault-injection control plane**: a test or the Spec 07 harness can
tell it to be slow, to fail, to drop responses, or to duplicate them, on demand.

This is what turns "we handle failures" into a reproducible demonstration.

## The partner API (what the saga calls)

```
POST /partner/authorize  {requestId, cardRef, amountMinor, currency, mcc, merchantId}
                         -> {requestId, partnerRef, approved, declineReason, duplicate}
POST /partner/capture    {requestId, partnerRef, amountMinor} -> {captured, duplicate}
POST /partner/reverse    {requestId, partnerRef}             -> {reversed, duplicate}
GET  /partner/state/{requestId} -> what the simulator actually did (404 if unknown)
```

**Idempotent on `requestId`.** A replay returns the original outcome with `duplicate=true`, and never
performs the side effect twice. This is the property that lets the saga safely retry and lets a
*late* response be absorbed: the saga derives a stable `requestId` from the authorization id, so a
retry or a recovery sweep converges on the same partner-side result. `GET /state/{requestId}` lets
the saga discover what really happened when a response was lost (e.g. to recover a `partnerRef` a
timeout hid).

## The fault control plane (what the harness drives)

```
POST /control/faults        { route, mode, ... }   apply a fault profile to a route
POST /control/faults/reset                          clear faults
GET  /control/faults                                inspect active profiles
```

Fault `mode`s (the failure shapes that matter for drift):

| Mode | What it simulates |
|------|-------------------|
| `NONE` | healthy |
| `LATENCY` | slow but eventually responds |
| `TIMEOUT` | hangs past the caller's bounded timeout |
| `FAIL_BEFORE_RESPONSE` | **did the work, then the response was lost** (the nastiest case) |
| `DECLINE` | a clean business decline |
| `ERROR_RATE` | a configurable fraction of calls 5xx |
| `DUPLICATE` | emits a duplicate side effect / response |
| `LATE_RESPONSE` | the response arrives *after* the caller already gave up and compensated |

`FAIL_BEFORE_RESPONSE` and `LATE_RESPONSE` are the heart of the "$20K drift" story: the partner
acted, but the caller didn't hear back in time and compensated. Because the partner is idempotent and
the saga compensates within a bounded timeout, the late success is absorbed with **zero drift**.

## Why it's a separate container (not a bean)

Per `docs/adr/ADR-0001`, the partner runs as its own service so the call is a **real network hop**.
In Spec 09's `docker-compose`, `app` reaches `partner-simulator` by service name over the compose
network — verifiably a separate process, not an in-JVM shortcut. If it were a bean, the most
important failure modes (connection drop, socket timeout) couldn't occur, and the proof would be
hollow.

## How the saga uses it (Spec 03) and how Spec 07 drives it

- **Spec 03 (auth saga):** calls `/partner/authorize|capture|reverse` under a hard bounded timeout;
  on timeout/5xx/no-response it runs a compensating reversal, relying on the partner's `requestId`
  idempotency to absorb retries and late successes.
- **Spec 07 (fault harness):** drives `/control/faults` to inject each mode while the load generator
  runs, then asserts reconciliation still reports **zero drift** — the visible proof that
  compensation + idempotency actually work against a real, misbehaving remote dependency.

## Tests

Happy-path authorize/capture/reverse, idempotent replay (`duplicate=true`, side effect once), the
control plane applying/resetting profiles, and the precise semantics of `FAIL_BEFORE_RESPONSE`
(side effect performed, response suppressed).
