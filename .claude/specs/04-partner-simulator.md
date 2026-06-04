# Spec 04 — Partner Simulator

| Field | Value |
|-------|-------|
| **Owns** | A separately-deployable service; injectable latency / failure / timeout on an external leg |
| **CV story** | BHN / Trimet SLA mismatch |
| **Depends on** | Spec 00 (it's a separate module); contracts with Spec 03 |
| **Blocks** | Spec 03 (auth saga calls it as its external leg), Spec 07 (fault injection) |
| **Parallelizable** | Yes — parallel batch with 02 / 05 / 06 |

## North star

Drift happens at network boundaries. To *prove* zero drift under failure you need a **real**
network boundary to fail against — not an in-JVM mock. The partner simulator is that boundary: a
genuinely separate process the auth saga calls over HTTP, with controllable latency, failures, and
timeouts. This is what turns "we handle failures" into a demonstrable property.

## The three invariants (how they apply here)

The simulator is **not** part of the ledger and holds no money. Its job is to *misbehave on
demand* so the rest of the system can prove it keeps the invariants. It must be able to:
fail after "doing the work" but before responding (the classic drift trigger), time out, and
return duplicate/late responses.

## Goal

Build a standalone Spring Boot service (`partner-simulator`) representing an external partner
(e.g. a network/scheme or downstream funding partner). Expose an endpoint the auth saga calls,
plus a **control plane** to inject faults at runtime so tests and demos can drive failure modes
deterministically.

## Scope / Deliverables

1. **Separate deployable** — own `main`, own port, own container (Spec 09 gives it its own service
   in compose). It must communicate with `app` only over HTTP, never in-process.

2. **Partner business endpoint** (the "external leg" the saga calls), e.g.:
   - `POST /partner/authorize` — accepts an auth request, returns approve/decline + a partner ref.
   - `POST /partner/capture` — confirms a capture.
   - `POST /partner/reverse` — reverses a prior auth.
   - Each is idempotent on a request id (echoes the original response on replay) so the saga's
     retries are safe.

3. **Control plane** to inject faults (per-route, runtime-configurable):
   - `latency` — add fixed/jittered delay (model an SLA mismatch).
   - `fail-before-response` — perform the side effect server-side, then drop the connection / 500
     **without** responding (the canonical drift trigger: partner did it, caller doesn't know).
   - `timeout` — exceed the caller's bounded timeout.
   - `decline` / `error-rate` — return declines or errors at a configured probability.
   - `duplicate` / `late-response` — respond twice or arrive after the saga already compensated.
   - Control via e.g. `POST /control/faults` with a profile, plus a `reset`. Faults are
     addressable per route and can be probabilistic or deterministic (next-N-requests).

4. **Deterministic mode for tests** — faults seedable/deterministic so CI is reproducible.

## Contract with Spec 03 (auth saga)

- The saga calls the simulator with a **bounded timeout**; on timeout/failure it must run a
  **compensating reversal** (saga owns that logic). The simulator's `fail-before-response` and
  `timeout` modes are exactly what exercises that compensation.
- The simulator endpoints are idempotent so the saga can retry safely.
- Request/response schema is defined jointly with Spec 03; keep it minimal and stable.

## Acceptance criteria (falsifiable)

- [ ] `partner-simulator` builds as its own boot jar and runs on its own port, independent of `app`.
- [ ] With no faults configured, `authorize`/`capture`/`reverse` behave normally and are
      idempotent on request id.
- [ ] `latency` mode delays responses by the configured amount.
- [ ] `fail-before-response` mode records the side effect server-side but returns no successful
      response to the caller (verifiable by querying the simulator's own state afterward).
- [ ] `timeout` mode causes the caller's bounded-timeout path to fire.
- [ ] `error-rate`/`decline` modes hit the configured probability over N requests (within
      tolerance) and are deterministic when seeded.
- [ ] `POST /control/faults` changes behavior at runtime; `reset` restores normal behavior.
- [ ] No money/ledger logic lives in this service.

## Gate

Spec 07's fault-injection harness drives this simulator to prove "injected downstream failures
never produce drift." Your fault modes must be rich enough to express every failure the saga
claims to survive.

## Out of scope

- Any ledger writes (this service holds no money).
- The compensation logic itself (Spec 03 owns it; you only trigger the conditions).
