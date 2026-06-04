# Driftless Documentation

Published project documentation: **what Driftless is, why it exists, how it's structured, and the
business logic behind the code** — with worked examples throughout.

> New here? Read in order: Overview → Architecture → Double-Entry Ledger → Invariants, then dive into
> the per-module pages.

## Core docs

| # | Doc | What it answers |
|---|-----|-----------------|
| 01 | [Overview & Business Case](./01-overview.md) | Why does this project need to exist? What problem does it solve? |
| 02 | [Architecture & Structure](./02-architecture.md) | How is the project organized and why? How does a request flow? |
| 03 | [Double-Entry Ledger](./03-double-entry-ledger.md) | The core accounting business logic, with a full worked example. |
| 04 | [The Three Invariants](./04-invariants.md) | Balance / Immutability / Idempotency — how each is enforced in code. |

## Module deep-dives

Each page answers the same three questions: **why is this module needed**, **what is its business
logic**, and **show me a worked example**.

| Module | Spec | Page |
|--------|------|------|
| ledger | 01 | [modules/ledger.md](./modules/ledger.md) |
| idempotency & outbox | 02 | [modules/idempotency.md](./modules/idempotency.md) |
| auth saga | 03 | [modules/auth.md](./modules/auth.md) |
| partner-simulator | 04 | [modules/partner-simulator.md](./modules/partner-simulator.md) |
| rules | 05 | [modules/rules.md](./modules/rules.md) |
| tokens | 06 | [modules/tokens.md](./modules/tokens.md) |
| reconciliation & reliability | 07 | [modules/recon.md](./modules/recon.md) |
| observability | 08 | [modules/observability.md](./modules/observability.md) |
| packaging & deploy | 09 | [modules/packaging.md](./modules/packaging.md) |

## Conventions used in these docs

- Money is shown as `$X.XX` but stored as integer **minor units** (`amountMinor`).
- Code references point at real packages/classes in the repo.
- Diagrams are ASCII so they render anywhere (including in a plain terminal).

## See also

- `docs/adr/` — Architecture Decision Records (local, not tracked).
- `docs/contracts/ledger-interface.md` — the frozen ledger contract (local, not tracked).
- `.claude/specs/` — the engineering specs that drove each module.
