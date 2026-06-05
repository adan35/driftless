# ADR-0001 — Modular monolith with one separate service

- Status: Accepted
- Date: 2026-06-01
- Deciders: Driftless core team

## Context

Driftless is an issuer-processor core whose signature property is **provable zero drift** under
downstream failure. To prove that property we need (a) tight, transactional cohesion between the
ledger and the modules that move money through it, and (b) at least one **real network boundary**
that can be made to time out, fail, or stall — because drift bugs hide in the seams between
processes, not inside a single transaction.

A full microservices topology would buy us independent deploys we do not yet need, at the cost of
distributed transactions, network flakiness, and operational overhead that would obscure the very
correctness story we are trying to tell. A single process with no external boundary, conversely,
could not demonstrate compensating reversals against a genuinely remote, independently-failing leg.

## Decision

Build Driftless as a **modular monolith** (`app`) — one runnable Spring Boot process composed of
independently-compiled Maven modules with enforced, strictly-inward dependencies — plus exactly
**one separately-deployable service**, `partner-simulator`, reached only over HTTP.

- `app` wires every feature module (`ledger`, `idempotency`, `auth`, `rules`, `tokens`, `recon`,
  `observability`) into a single context and exposes the REST API.
- Modules integrate through published `api`/`spi` packages and outbox events, never through each
  other's internals. The dependency graph has no cycles and points inward toward
  `ledger`/`common`.
- `partner-simulator` shares no in-JVM call path with `app`; it is a real external dependency the
  auth saga calls under a hard timeout and compensates against on failure.

## Consequences

- The monolith stays transactional and easy to reason about; the ledger and saga share a database
  transaction where correctness demands it.
- Module boundaries are real (separate compilation units, no back-references), so the codebase is
  **microservices-ready** without paying distributed-systems costs prematurely.
- We retain a genuine failure boundary to inject latency/timeouts/5xx against, which is what makes
  the zero-drift proof credible.
- `app` and `partner-simulator` each produce their own runnable boot jar and own container.
