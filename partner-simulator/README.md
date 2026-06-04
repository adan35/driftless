# partner-simulator (Spec 04)

A standalone Spring Boot service that gives Driftless a **real network boundary to fail against**.
The auth saga (Spec 03) calls it over HTTP only — it shares no process or in-JVM call path with the
monolith `app`, depends on no internal Driftless module, and **holds no money or ledger logic**
(`amountMinor` / `currency` are opaque pass-through fields, never summed or interpreted).

Its job is to behave like a partner network/funding leg — and to **misbehave on demand** so the rest
of the system can prove it keeps the three invariants under failure.

- Runs on its own port (`server.port=8081`) and builds its own runnable boot jar.
- Run: `java -jar partner-simulator/target/partner-simulator-0.0.1-SNAPSHOT.jar`
- Build/test this module only: `./mvnw verify -pl partner-simulator -am`

## Partner business API (the external leg the saga calls)

Each leg is **idempotent on `requestId`**: a replay with the same id echoes the original response and
records no new work, so the saga's retries are safe.

### `POST /partner/authorize`

Request:

```json
{
  "requestId": "string (required, idempotency key)",
  "cardRef":   "string (required, opaque token — never a raw PAN)",
  "amountMinor": 1500,
  "currency":  "USD",
  "mcc":       "5411 (optional)",
  "merchantId":"merchant-1 (optional)"
}
```

Response:

```json
{
  "requestId":    "echoes the request id",
  "partnerRef":   "AUTH-<uuid> (partner-assigned, stable across replays)",
  "approved":     true,
  "declineReason":null,
  "duplicate":    false
}
```

### `POST /partner/capture`

Request: `{ "requestId": "...", "partnerRef": "AUTH-...", "amountMinor": 1500 }`

Response: `{ "requestId": "...", "partnerRef": "AUTH-...", "captured": true, "duplicate": false }`

### `POST /partner/reverse`

Request: `{ "requestId": "...", "partnerRef": "AUTH-..." }`

Response: `{ "requestId": "...", "partnerRef": "AUTH-...", "reversed": true, "duplicate": false }`

### `GET /partner/state/{requestId}`

Read the simulator's own record of what it actually did for a request id (`200` if known, `404`
otherwise). This is how a test proves `fail-before-response` performed the work server-side even
though the caller got no success.

```json
{
  "requestId": "...",
  "known": true,
  "route": "AUTHORIZE",
  "responseDelivered": false,
  "sideEffectsPerformed": true,
  "sideEffects": [
    { "route": "AUTHORIZE", "partnerRef": "AUTH-...", "delivered": false, "epochMillis": 0 }
  ],
  "response": null
}
```

`delivered=false` marks work performed without the caller learning of it — the canonical drift trigger.

## Control plane (runtime fault injection)

Faults are **addressable per route** (`AUTHORIZE` / `CAPTURE` / `REVERSE`) and take effect on the
next matching request — no restart needed.

### `POST /control/faults`

Apply (replace) one route's fault profile. Body:

```json
{
  "route":        "AUTHORIZE | CAPTURE | REVERSE (required)",
  "mode":         "NONE | LATENCY | FAIL_BEFORE_RESPONSE | TIMEOUT | DECLINE | ERROR_RATE | DUPLICATE | LATE_RESPONSE (required)",
  "delayMillis":  0,
  "jitterMillis": 0,
  "probability":  0.0,
  "applyToNext":  0,
  "seed":         0,
  "declineReason":null
}
```

Validation (returns `400`): `delayMillis > 0` is required for `LATENCY` / `TIMEOUT` / `LATE_RESPONSE`;
`probability > 0` is required for `DECLINE` / `ERROR_RATE`.

### `POST /control/faults/reset`

Restore normal behaviour on every route. `?state=true` also clears recorded request state (full test
isolation); default clears faults only. Returns `204`.

### `GET /control/faults`

Returns the active profile per route (a `{route: profile}` map).

## Fault modes

| Mode | Behaviour |
|------|-----------|
| `NONE` | Normal: perform the work, return success. |
| `LATENCY` | Sleep `delayMillis` (+ uniform `jitterMillis`) then respond normally. Models an SLA mismatch. |
| `FAIL_BEFORE_RESPONSE` | Perform the side effect and record it, then return `500` with **no** success — the partner did the work but the caller never learns it (the canonical drift trigger). |
| `TIMEOUT` | Sleep `delayMillis` (set it beyond the caller's bounded timeout) so the caller's timeout path fires; the work is not performed. |
| `DECLINE` | Return `approved=false` with `declineReason` at `probability` (authorize leg). |
| `ERROR_RATE` | Return `500` at `probability`. |
| `DUPLICATE` | Perform the work once and emit a response flagged `duplicate=true`; a later replay returns the stored non-duplicate response. |
| `LATE_RESPONSE` | Perform the work, then sleep `delayMillis` and respond (a late response that may arrive after the saga compensated). |

## Determinism (reproducible CI)

Probabilistic modes (`DECLINE` / `ERROR_RATE`) and jittered delays are driven by an RNG **seeded from
`seed`**, re-seeded each time a profile is applied. The same `seed` over the same request sequence
produces the same outcomes, so CI assertions on hit-rate are stable.

For exact, RNG-free control set `applyToNext = N`: the fault fires for the **next N matching requests**
then auto-heals to `NONE` (deterministic next-N-requests mode).

## Contract with Spec 03

The saga calls these legs under a **hard bounded timeout** and, on timeout / `fail-before-response` /
`5xx`, runs a **compensating reversal** (the saga owns that logic; this service only triggers the
conditions). The idempotent legs make the saga's retries safe. Keep this request/response schema
stable — Spec 03 codes against it.
