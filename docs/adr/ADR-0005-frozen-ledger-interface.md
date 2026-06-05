# ADR-0005 — Frozen public ledger interface

- Status: Accepted
- Date: 2026-06-01
- Deciders: Driftless core team

## Context

The build plan runs modules `02 / 04 / 05 / 06` (idempotency, partner-simulator, rules, tokens) in
a **parallel batch** once the ledger's public interface exists, then layers `03` (auth saga) and
`07` (reconciliation) on top. Parallel work against a *moving* API guarantees integration drift:
agents code to slightly different shapes and the pieces do not fit. The whole parallelization
strategy is only safe if the cross-module contract is stable before the batch starts.

## Decision

Publish Spec 01's public ledger interface in the `ledger` module under `io.driftless.ledger.api`
(plus the `io.driftless.ledger.spi.HoldView` SPI) and **freeze it** as part of the Spec 00 gate,
before any parallel agent runs.

- The frozen surface is documented in
  [`docs/contracts/ledger-interface.md`](../contracts/ledger-interface.md): enums `Direction` and
  `AccountType`; records `Account`, `PostingLine`, `PostingRequest`, `PostingResult`, `Balance`,
  `JournalEntry`, `Transaction`, `Page`; the `Ledger` interface (five methods); the
  `BalanceInvariantViolation` exception; and the `HoldView` SPI.
- The `api` and `spi` packages carry a `package-info.java` that marks them FROZEN and points at the
  contract doc.
- At Spec 00 these are **contract shapes only** — interfaces, records, enums, and the exception —
  with **no implementation** (no `LedgerImpl`, no JPA, no SQL). Spec 01 supplies the implementation
  behind the `Ledger` interface; that implementation is not part of the frozen surface.

## Consequences

- Modules `02 / 04 / 05 / 06` build against a stable contract, making the parallel batch safe.
- Any change to the frozen surface requires re-opening the Spec 00/01 gate, sign-off from the
  reconciliation-correctness owner, and a coordinated update of every consumer — it is deliberately
  expensive.
- The `ledger` module depends only on `common`, preserving the strictly-inward dependency
  direction; downstream modules depend on `ledger` + `common`, never on each other's internals.
- Because the frozen module is interfaces/records only at this stage, it carries no JaCoCo coverage
  floor; Spec 01 adds the implementation and raises the floor.
