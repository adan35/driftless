# ADR-0004 — Append-only ledger + compensating entries

- Status: Accepted
- Date: 2026-06-01
- Deciders: Driftless core team

## Context

A double-entry ledger is the system of record for money. If posted history can be mutated, then
audit trails are untrustworthy, reconciliation cannot prove correctness, and a single bad `UPDATE`
can introduce drift that is invisible after the fact. Real issuer-processor cores never edit settled
history; they correct it forward.

## Decision

The ledger is **append-only**. Once written, a `journal_entry` is never updated or deleted.

- Corrections and reversals are **new, balanced `post()` calls** with reversing direction — never a
  mutation of an existing entry.
- There is no `UPDATE`/`DELETE` code path against `journal_entry`. Where practical this is enforced
  at the database level (a trigger or a revoked-privilege migration), and covered by an integration
  test that asserts a direct mutation attempt is rejected.
- Every write is balanced: per currency, `SUM(amount WHERE DEBIT) == SUM(amount WHERE CREDIT)`. An
  unbalanced `PostingRequest` is rejected with a typed `BalanceInvariantViolation` and nothing is
  written.
- Balance is **derived, never stored** — always `SELECT SUM(...)` over entries. Any snapshot/cache
  added later (Spec 07) is derived and reconcilable, never authoritative.

This encodes invariants 1 (Balance) and 2 (Immutability) from `CLAUDE.md`.

## Consequences

- The full history of every account is reconstructible and auditable; reconciliation can prove the
  global sum is zero at any time.
- Mistakes are fixed by compensating entries, which themselves remain in the record — the correction
  is visible, not hidden.
- Slightly more rows than a mutate-in-place design, accepted as the cost of correctness and
  auditability; summation is indexed on `journal_entry(account_id)`.
- "Fix wrong logic deliberately" applies to code, not to settled rows: bugs are corrected by new
  compensating posts, never by editing the ledger.
